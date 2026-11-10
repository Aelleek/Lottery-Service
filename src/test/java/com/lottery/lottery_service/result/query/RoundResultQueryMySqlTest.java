package com.lottery.lottery_service.result.query;

import static org.assertj.core.api.Assertions.assertThat;

import com.lottery.lottery_service.account.AccountStore;
import com.lottery.lottery_service.auth.OAuthProvider;
import com.lottery.lottery_service.common.db.DbValues;
import com.lottery.lottery_service.common.job.JobProperties;
import com.lottery.lottery_service.common.job.JobRunner;
import com.lottery.lottery_service.common.job.JobStore;
import com.lottery.lottery_service.common.number.LottoNumber;
import com.lottery.lottery_service.common.number.LottoNumbers;
import com.lottery.lottery_service.result.domain.PrizeRank;
import com.lottery.lottery_service.result.domain.WinningNumbers;
import com.lottery.lottery_service.result.evaluate.InstantCheck;
import com.lottery.lottery_service.result.publish.PrizeProperties;
import com.lottery.lottery_service.result.publish.ResultPublisher;
import com.lottery.lottery_service.result.source.DrawSource;
import com.lottery.lottery_service.result.source.OfficialDraw;
import com.lottery.lottery_service.result.source.ParsedDraw;
import com.lottery.lottery_service.result.source.ResultCollectJobHandler;
import com.lottery.lottery_service.result.source.ResultCollector;
import com.lottery.lottery_service.support.MutableClock;
import com.lottery.lottery_service.support.MySqlContainerSupport;
import java.time.Instant;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/** 회차 결과 조회와 즉시 확인을 MySQL에서 확인한다. */
class RoundResultQueryMySqlTest extends MySqlContainerSupport {

  private static final int ROUND = 1200;
  private static final Instant DRAW_AT = Instant.parse("2026-11-14T11:35:00Z");
  private static final OfficialDraw DRAW =
      new OfficialDraw(
          ROUND,
          new WinningNumbers(LottoNumbers.of(1, 2, 3, 4, 5, 6), LottoNumber.of(7)),
          2_000_000_000L,
          12L,
          new byte[32]);

  private static DataSource dataSource;

  private final MutableClock clock = new MutableClock(DRAW_AT.plusSeconds(600));
  private JdbcTemplate jdbc;
  private DataSourceTransactionManager tm;
  private JobStore jobs;
  private ResultPublisher publisher;
  private RoundResultQuery query;

  @BeforeAll
  static void migrate() throws Exception {
    dataSource = migratedDatabase("round_result_query");
  }

  @BeforeEach
  void setUp() {
    jdbc = new JdbcTemplate(dataSource);
    jdbc.update("DELETE FROM job");
    jdbc.update("UPDATE lotto_round SET published_result_id = NULL");
    jdbc.update("DELETE FROM draw_prize");
    jdbc.update("DELETE FROM draw_result");
    jdbc.update("DELETE FROM lotto_round");
    jdbc.update(
        "INSERT INTO lotto_round (round_no, sales_open_at, sales_close_at, draw_at, schedule_version)"
            + " VALUES (?, '2026-11-07 21:00:00', '2026-11-14 11:00:00', '2026-11-14 11:35:00', 1)",
        ROUND);
    tm = new DataSourceTransactionManager(dataSource);
    jobs = new JobStore(jdbc, tm, clock, new JobProperties(null, null));
    publisher = new ResultPublisher(jdbc, tm, jobs, new PrizeProperties(null, null), clock);
    query = new RoundResultQuery(jdbc);
  }

  @Test
  @DisplayName("등록되지 않은 회차는 빈 값, 수집 전에는 미발표이고 확인 시각이 없다")
  void unknownAndNotAttempted() {
    assertThat(query.find(ROUND + 1)).isEmpty();

    RoundResult result = query.find(ROUND).orElseThrow();

    assertThat(result.state()).isEqualTo(ResultState.NOT_PUBLISHED);
    assertThat(result.completeness()).isEqualTo("NONE");
    assertThat(result.publicationVersion()).isNull();
    assertThat(result.prizes()).isEmpty();
    assertThat(result.sourceCheckedAt()).isNull();
  }

  @Test
  @DisplayName("마지막 수집 시도가 미발표면 NOT_PUBLISHED, 수집 실패면 DELAYED이고 확인 시각은 그 시도가 끝난 시각이다")
  void notAnnouncedThenFetchError() {
    collect(r -> new ParsedDraw.NotAnnounced());
    RoundResult notAnnounced = query.find(ROUND).orElseThrow();
    assertThat(notAnnounced.state()).isEqualTo(ResultState.NOT_PUBLISHED);
    assertThat(notAnnounced.sourceCheckedAt()).isEqualTo(clock.instant());

    clock.set(DRAW_AT.plusSeconds(900));
    collect(r -> new ParsedDraw.Invalid("NETWORK"));
    RoundResult delayed = query.find(ROUND).orElseThrow();
    assertThat(delayed.state()).isEqualTo(ResultState.DELAYED);
    assertThat(delayed.sourceCheckedAt()).isEqualTo(clock.instant());

    clock.set(DRAW_AT.plusSeconds(1200));
    collect(r -> new ParsedDraw.NotAnnounced());
    assertThat(query.find(ROUND).orElseThrow().state()).isEqualTo(ResultState.NOT_PUBLISHED);
  }

  @Test
  @DisplayName("게시되면 번호·보너스·게시 버전과 등수별 금액을 주고, 2·3등 금액이 없으면 PARTIAL_PRIZES(D-046)")
  void published() {
    collect(r -> new ParsedDraw.Invalid("NETWORK"));
    clock.set(DRAW_AT.plusSeconds(900));
    collect(r -> new ParsedDraw.Valid(DRAW));

    RoundResult result = query.find(ROUND).orElseThrow();

    assertThat(result.state()).isEqualTo(ResultState.PUBLISHED);
    assertThat(result.winning()).isEqualTo(DRAW.winning());
    assertThat(result.publicationVersion()).isEqualTo(1L);
    assertThat(result.completeness()).isEqualTo("PARTIAL_PRIZES");
    assertThat(result.prizes())
        .containsExactly(
            new Prize(1, 2_000_000_000L, 12L),
            new Prize(2, null, null),
            new Prize(3, null, null),
            new Prize(4, 50_000L, null),
            new Prize(5, 5_000L, null));
    assertThat(result.prizePerGame(PrizeRank.FOURTH)).isEqualTo(50_000L);
    assertThat(result.prizePerGame(PrizeRank.SECOND)).isNull();
    assertThat(result.prizePerGame(PrizeRank.NONE)).isNull();
    assertThat(result.sourceCheckedAt()).isNotNull();
    assertThat(result.source()).isEqualTo("API");
    assertThat(result.apiConfirmedAt()).isNull();
  }

  @Test
  @DisplayName("직접 등록한 결과는 출처 MANUAL이고 API 확인 전이며, 같은 API 결과가 오면 확인 시각을 준다(D-061)")
  void manualResultShowsConfirmation() {
    jdbc.update(
        "UPDATE lotto_round SET draw_at = NOW(6) - INTERVAL 1 HOUR WHERE round_no = ?", ROUND);
    long admin =
        new AccountStore(jdbc, tm, clock)
            .login(OAuthProvider.GOOGLE, "admin-" + System.nanoTime())
            .memberId();
    publisher.registerManual(ROUND, DRAW.winning(), admin, 0);

    RoundResult manual = query.find(ROUND).orElseThrow();
    assertThat(manual.source()).isEqualTo("MANUAL");
    assertThat(manual.apiConfirmedAt()).isNull();

    publisher.receive(DRAW);
    RoundResult confirmed = query.find(ROUND).orElseThrow();
    assertThat(confirmed.source()).isEqualTo("MANUAL");
    assertThat(confirmed.apiConfirmedAt()).isNotNull();
  }

  @Test
  @DisplayName("즉시 확인은 게시 전이면 등수 없이 상태만, 게시 뒤에는 입력 순서대로 등수를 주고 아무것도 저장하지 않는다")
  void instantCheck() {
    InstantCheck check = new InstantCheck(query);
    List<LottoNumbers> sets =
        List.of(LottoNumbers.of(1, 2, 3, 4, 5, 6), LottoNumbers.of(1, 2, 3, 40, 41, 42));
    assertThat(check.check(ROUND + 1, sets)).isEmpty();
    InstantCheck.Checked before = check.check(ROUND, sets).orElseThrow();
    assertThat(before.ranks()).isEmpty();
    assertThat(before.result().state()).isEqualTo(ResultState.NOT_PUBLISHED);

    publisher.receive(DRAW);
    long jobsBefore = jdbc.queryForObject("SELECT COUNT(*) FROM job", Long.class);
    InstantCheck.Checked after = check.check(ROUND, sets).orElseThrow();

    assertThat(after.ranks()).containsExactly(PrizeRank.FIRST, PrizeRank.FIFTH);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lotto_record", Long.class)).isZero();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM job", Long.class)).isEqualTo(jobsBefore);
  }

  private void collect(DrawSource source) {
    ResultCollectJobHandler handler =
        new ResultCollectJobHandler(new ResultCollector(source, publisher), jobs, jdbc, tm, clock);
    handler.plan(ROUND, DRAW_AT);
    jdbc.update(
        "UPDATE job SET available_at = ? WHERE state = 'READY'", DbValues.utc(clock.instant()));
    new JobRunner(jobs, List.of(handler), tm, clock).runOnce();
  }
}
