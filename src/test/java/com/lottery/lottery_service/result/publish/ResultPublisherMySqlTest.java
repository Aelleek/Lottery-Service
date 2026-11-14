package com.lottery.lottery_service.result.publish;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lottery.lottery_service.account.AccountStore;
import com.lottery.lottery_service.auth.OAuthProvider;
import com.lottery.lottery_service.common.error.BusinessException;
import com.lottery.lottery_service.common.error.ErrorCode;
import com.lottery.lottery_service.common.job.JobProperties;
import com.lottery.lottery_service.common.job.JobStore;
import com.lottery.lottery_service.common.number.LottoNumber;
import com.lottery.lottery_service.common.number.LottoNumbers;
import com.lottery.lottery_service.result.domain.WinningNumbers;
import com.lottery.lottery_service.result.source.OfficialDraw;
import com.lottery.lottery_service.result.source.RankPrize;
import com.lottery.lottery_service.round.RoundStore;
import com.lottery.lottery_service.round.domain.RoundSchedule;
import com.lottery.lottery_service.support.MySqlContainerSupport;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/** 결과 게시, 격리, 직접 등록, 승인을 MySQL에서 확인한다. */
class ResultPublisherMySqlTest extends MySqlContainerSupport {

  private static final int ROUND = 1200;
  private static final WinningNumbers WIN =
      new WinningNumbers(LottoNumbers.of(3, 11, 19, 27, 36, 44), LottoNumber.of(7));
  private static final WinningNumbers OTHER =
      new WinningNumbers(LottoNumbers.of(3, 11, 19, 27, 36, 45), LottoNumber.of(7));

  private static DataSource dataSource;

  private JdbcTemplate jdbc;
  private ResultPublisher publisher;
  private RoundStore rounds;
  private long admin;

  @BeforeAll
  static void migrate() throws Exception {
    dataSource = migratedDatabase("result_publisher");
  }

  @BeforeEach
  void setUp() {
    jdbc = new JdbcTemplate(dataSource);
    jdbc.update("DELETE FROM job");
    jdbc.update("UPDATE lotto_round SET published_result_id = NULL");
    jdbc.update("DELETE FROM draw_prize");
    jdbc.update("DELETE FROM draw_result");
    jdbc.update("DELETE FROM lotto_round");
    jdbc.update("DELETE FROM oauth_account");
    jdbc.update("DELETE FROM actor_subject");
    jdbc.update("DELETE FROM member");
    DataSourceTransactionManager tm = new DataSourceTransactionManager(dataSource);
    rounds = new RoundStore(jdbc, tm);
    Instant now = rounds.dbNow();
    rounds.register(
        new RoundSchedule(
            ROUND,
            now.minus(Duration.ofDays(7)),
            now.minus(Duration.ofHours(1)),
            now.minusSeconds(60),
            1));
    Clock clock = Clock.fixed(Instant.parse("2026-11-14T12:00:00Z"), ZoneOffset.UTC);
    publisher =
        new ResultPublisher(
            jdbc,
            tm,
            new JobStore(jdbc, tm, clock, new JobProperties(null, null)),
            new PrizeProperties(null, null),
            clock);
    admin =
        jdbc.queryForObject(
            "SELECT member_id FROM actor_subject WHERE id = ?",
            Long.class,
            new AccountStore(jdbc, tm, clock).login(OAuthProvider.GOOGLE, "admin").subjectId());
  }

  @Test
  @DisplayName("처음 받은 결과는 게시본 1이 되고 게시 버전이 오르며, 1등은 API 금액, 4·5등은 설정값, 2·3등은 비어 있다(T57)")
  void firstPublish() {
    assertThat(publisher.receive(draw(WIN, 1_000_000L))).isEqualTo(ReceiveOutcome.PUBLISHED);

    Map<String, Object> round = round();
    assertThat(round.get("publication_version")).isEqualTo(1L);
    assertThat(round.get("published_result_id")).isNotNull();
    assertThat(prizes()).containsExactly("1:1000000", "2:null", "3:null", "4:50000", "5:5000");
    assertThat(count("job WHERE job_type = 'EVALUATE_RESULT'")).isEqualTo(1);
  }

  @Test
  @DisplayName("1~3등 금액을 API 값으로 채우고, 처음에 없던 2·3등 금액은 같은 결과를 다시 받을 때 채운다(D-060)")
  void upperPrizesFromApi() {
    publisher.receive(
        new OfficialDraw(
            ROUND,
            WIN,
            List.of(
                new RankPrize(1, 2_000_000L, 2L),
                new RankPrize(2, null, 5L),
                new RankPrize(3, null, null)),
            new byte[32]));
    assertThat(prizes()).containsExactly("1:2000000", "2:null", "3:null", "4:50000", "5:5000");

    assertThat(
            publisher.receive(
                new OfficialDraw(
                    ROUND,
                    WIN,
                    List.of(
                        new RankPrize(1, 9_999_999L, 9L),
                        new RankPrize(2, 50_000_000L, 5L),
                        new RankPrize(3, 1_300_000L, 300L)),
                    new byte[32])))
        .isEqualTo(ReceiveOutcome.SAME_AS_PUBLISHED);
    assertThat(prizes())
        .containsExactly("1:2000000", "2:50000000", "3:1300000", "4:50000", "5:5000");
    assertThat(
            jdbc.queryForList(
                "SELECT p.winner_count FROM draw_prize p JOIN lotto_round r"
                    + " ON r.published_result_id = p.result_id WHERE p.rank_no <= 3"
                    + " ORDER BY p.rank_no",
                Long.class))
        .containsExactly(2L, 5L, 300L);
  }

  @Test
  @DisplayName("의미가 같은 결과를 다시 받으면 게시본을 새로 만들지 않고, 다른 결과는 격리하고 바뀐 항목을 담은 운영자 알림 1건(T14)")
  void sameAndDifferent() {
    publisher.receive(draw(WIN, null));

    assertThat(publisher.receive(draw(WIN, 2_000_000L)))
        .isEqualTo(ReceiveOutcome.SAME_AS_PUBLISHED);
    assertThat(prizes()).startsWith("1:2000000");
    assertThat(round().get("publication_version")).isEqualTo(1L);

    assertThat(publisher.receive(draw(OTHER, null))).isEqualTo(ReceiveOutcome.QUARANTINED);
    assertThat(count("draw_result WHERE state = 'QUARANTINED'")).isEqualTo(1);
    assertThat(count("job WHERE job_type = 'OPERATOR_ALERT'")).isEqualTo(1);
    assertThat(round().get("publication_version")).isEqualTo(1L);

    assertThat(
            publisher.receive(
                draw(
                    new WinningNumbers(LottoNumbers.of(3, 11, 19, 27, 36, 44), LottoNumber.of(8)),
                    null)))
        .isEqualTo(ReceiveOutcome.QUARANTINED);
    assertThat(
            jdbc.queryForList(
                "SELECT CAST(JSON_EXTRACT(payload, '$.changed') AS CHAR) FROM job"
                    + " WHERE job_type = 'OPERATOR_ALERT' ORDER BY id",
                String.class))
        .containsExactly("[\"NUMBERS\"]", "[\"BONUS\"]");
  }

  @Test
  @DisplayName("격리된 결과와 같은 내용이 계속 와도 격리 행 1개, 운영자 알림 1건(T49)")
  void repeatedQuarantine() {
    publisher.receive(draw(WIN, null));
    for (int i = 0; i < 5; i++) {
      publisher.receive(draw(OTHER, null));
    }

    assertThat(count("draw_result WHERE state = 'QUARANTINED'")).isEqualTo(1);
    assertThat(count("job WHERE job_type = 'OPERATOR_ALERT'")).isEqualTo(1);
  }

  @Test
  @DisplayName("승인하면 격리 결과가 새 게시본이 되고, 이전 게시본은 SUPERSEDED, 이전 판정 job은 취소 요청, 새 판정 job 등록")
  void approve() {
    publisher.receive(draw(WIN, null));
    publisher.receive(draw(OTHER, null));
    long quarantined =
        jdbc.queryForObject("SELECT id FROM draw_result WHERE state = 'QUARANTINED'", Long.class);

    assertCode(() -> publisher.approve(quarantined, 0), ErrorCode.VERSION_CONFLICT);
    publisher.approve(quarantined, 1);

    assertThat(round().get("published_result_id")).isEqualTo(quarantined);
    assertThat(round().get("publication_version")).isEqualTo(2L);
    assertThat(count("draw_result WHERE state = 'SUPERSEDED'")).isEqualTo(1);
    assertThat(count("job WHERE job_type = 'EVALUATE_RESULT' AND cancel_requested = TRUE"))
        .isEqualTo(1);
    assertThat(count("job WHERE job_type = 'EVALUATE_RESULT'")).isEqualTo(2);
  }

  @Test
  @DisplayName("격리된 결과가 남아 있는 동안 회차 정리를 보류하고, 격리가 모두 승인되면 보류를 푼다(04 5절)")
  void quarantineHoldsCleanup() {
    publisher.receive(draw(WIN, null));
    assertThat(cleanupHeld()).isFalse();

    publisher.receive(draw(OTHER, null));
    assertThat(cleanupHeld()).isTrue();
    publisher.receive(
        draw(new WinningNumbers(LottoNumbers.of(3, 11, 19, 27, 36, 44), LottoNumber.of(8)), null));
    List<Long> quarantined =
        jdbc.queryForList(
            "SELECT id FROM draw_result WHERE state = 'QUARANTINED' ORDER BY id", Long.class);

    publisher.approve(quarantined.get(0), 1);
    assertThat(cleanupHeld()).isTrue();
    publisher.approve(quarantined.get(1), 2);
    assertThat(cleanupHeld()).isFalse();
  }

  @Test
  @DisplayName("직접 등록은 즉시 게시되고 금액은 비어 있다. 나중에 같은 API 결과가 오면 확인 표시만, 다르면 격리(T47)")
  void manualRegistration() {
    long id = publisher.registerManual(ROUND, WIN, admin, 0);

    assertThat(round().get("published_result_id")).isEqualTo(id);
    assertThat(
            jdbc.queryForList(
                "SELECT CAST(payload AS CHAR) FROM job WHERE job_type = 'OPERATOR_ALERT'",
                String.class))
        .containsExactly("{\"kind\": \"RESULT_MANUAL_REGISTERED\", \"roundNo\": " + ROUND + "}");
    assertThat(prizes()).startsWith("1:null");
    assertThat(publisher.receive(draw(WIN, 3_000_000L)))
        .isEqualTo(ReceiveOutcome.SAME_AS_PUBLISHED);
    assertThat(
            jdbc.queryForObject(
                "SELECT api_confirmed_at IS NOT NULL FROM draw_result WHERE id = ?",
                Boolean.class,
                id))
        .isTrue();
    assertThat(publisher.receive(draw(OTHER, null))).isEqualTo(ReceiveOutcome.QUARANTINED);
  }

  @Test
  @DisplayName(
      "이미 게시된 회차의 직접 등록은 RESULT_ALREADY_PUBLISHED, 버전이 다르면 VERSION_CONFLICT, 추첨 전이면 DRAW_NOT_FINISHED")
  void manualRegistrationRejected() {
    assertCode(() -> publisher.registerManual(ROUND, WIN, admin, 3), ErrorCode.VERSION_CONFLICT);
    jdbc.update(
        "UPDATE lotto_round SET draw_at = NOW(6) + INTERVAL 1 HOUR WHERE round_no = ?", ROUND);
    assertCode(() -> publisher.registerManual(ROUND, WIN, admin, 0), ErrorCode.DRAW_NOT_FINISHED);
    jdbc.update(
        "UPDATE lotto_round SET draw_at = NOW(6) - INTERVAL 1 HOUR WHERE round_no = ?", ROUND);
    publisher.receive(draw(WIN, null));
    assertCode(
        () -> publisher.registerManual(ROUND, WIN, admin, 1), ErrorCode.RESULT_ALREADY_PUBLISHED);
  }

  private static OfficialDraw draw(WinningNumbers winning, Long firstPrize) {
    return new OfficialDraw(
        ROUND, winning, firstPrize, firstPrize == null ? null : 3L, new byte[32]);
  }

  private Map<String, Object> round() {
    return jdbc.queryForMap(
        "SELECT published_result_id, publication_version FROM lotto_round WHERE round_no = ?",
        ROUND);
  }

  private boolean cleanupHeld() {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT cleanup_held FROM lotto_round WHERE round_no = ?", Boolean.class, ROUND));
  }

  private List<String> prizes() {
    return jdbc.queryForList(
        "SELECT CONCAT(p.rank_no, ':', COALESCE(p.prize_per_game, 'null')) FROM draw_prize p"
            + " JOIN lotto_round r ON r.published_result_id = p.result_id ORDER BY p.rank_no",
        String.class);
  }

  private long count(String from) {
    return jdbc.queryForObject("SELECT COUNT(*) FROM " + from, Long.class);
  }

  private static void assertCode(Runnable call, ErrorCode code) {
    assertThatThrownBy(call::run)
        .isInstanceOfSatisfying(
            BusinessException.class, e -> assertThat(e.getCode()).isEqualTo(code));
  }
}
