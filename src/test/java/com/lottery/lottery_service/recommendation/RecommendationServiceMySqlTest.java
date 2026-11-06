package com.lottery.lottery_service.recommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lottery.lottery_service.account.GuestCredentialStore;
import com.lottery.lottery_service.common.error.BusinessException;
import com.lottery.lottery_service.common.error.ErrorCode;
import com.lottery.lottery_service.common.number.LottoNumbers;
import com.lottery.lottery_service.recommendation.filter.DifferenceKindsFilter;
import com.lottery.lottery_service.recommendation.filter.EndDigitSumFilter;
import com.lottery.lottery_service.recommendation.filter.GenerationFilterPipeline;
import com.lottery.lottery_service.recommendation.filter.LowHighFilter;
import com.lottery.lottery_service.recommendation.filter.OddEvenFilter;
import com.lottery.lottery_service.recommendation.filter.PastFirstPrizeFilter;
import com.lottery.lottery_service.recommendation.filter.SameEndDigitFilter;
import com.lottery.lottery_service.recommendation.filter.ThreeZoneFilter;
import com.lottery.lottery_service.recommendation.generator.RecommendationCondition;
import com.lottery.lottery_service.recommendation.generator.RecommendationGenerator;
import com.lottery.lottery_service.round.RoundStore;
import com.lottery.lottery_service.round.domain.RoundSchedule;
import com.lottery.lottery_service.stats.UsageEventStore;
import com.lottery.lottery_service.support.MutableClock;
import com.lottery.lottery_service.support.MySqlContainerSupport;
import com.lottery.lottery_service.support.TestSecrets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.SplittableRandom;
import java.util.stream.IntStream;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/** 추천 한 번의 전체 흐름(생성 권한, 생성, 잠금 뒤 저장)을 MySQL에서 확인한다. */
class RecommendationServiceMySqlTest extends MySqlContainerSupport {

  private static final Instant T0 = Instant.parse("2026-11-08T01:00:00Z");
  private static final int ROUND = 1200;

  private static DataSource dataSource;

  private final MutableClock clock = new MutableClock(T0);
  private JdbcTemplate jdbc;
  private RoundStore rounds;
  private RecommendationService service;
  private RoundSchedule schedule;
  private long subject;

  @BeforeAll
  static void migrate() throws Exception {
    dataSource = migratedDatabase("recommendation_service");
  }

  @BeforeEach
  void setUp() {
    jdbc = new JdbcTemplate(dataSource);
    for (String table :
        List.of(
            "usage_event",
            "recommendation_seen",
            "recommendation_item",
            "recommendation_request",
            "lotto_record",
            "guest_credential",
            "actor_subject")) {
      jdbc.update("DELETE FROM " + table);
    }
    jdbc.update("UPDATE lotto_round SET published_result_id = NULL");
    jdbc.update("DELETE FROM draw_result");
    jdbc.update("DELETE FROM lotto_round");
    DataSourceTransactionManager tm = new DataSourceTransactionManager(dataSource);
    rounds = new RoundStore(jdbc, tm);
    Instant dbNow = rounds.dbNow();
    schedule =
        new RoundSchedule(
            ROUND,
            dbNow.minus(Duration.ofDays(1)),
            dbNow.plus(Duration.ofHours(1)),
            dbNow.plus(Duration.ofHours(2)),
            1);
    rounds.register(schedule);
    subject = new GuestCredentialStore(jdbc, tm, clock, TestSecrets.keys()).issue().subjectId();
    GenerationFilterPipeline filters =
        new GenerationFilterPipeline(
            List.of(
                new PastFirstPrizeFilter(),
                new EndDigitSumFilter(),
                new SameEndDigitFilter(),
                new LowHighFilter(),
                new OddEvenFilter(),
                new DifferenceKindsFilter(),
                new ThreeZoneFilter()));
    service =
        new RecommendationService(
            new GenerationLeaseStore(jdbc, tm, clock),
            new RecommendationStore(jdbc),
            rounds,
            new PublishedFirstPrizes(jdbc),
            filters,
            new RecommendationGenerator(new SplittableRandom(5), System::nanoTime),
            new UsageEventStore(jdbc),
            tm);
  }

  @Test
  @DisplayName("기본 추천은 5세트와 필터 버전, 사용량 사건 2개를 남기고 원본 보관 기한은 추첨일+30일이다")
  void basicRecommendation() {
    SavedRecommendation saved = service.recommend(basic(1));

    assertThat(saved.recordIds()).hasSize(5);
    assertThat(
            jdbc.queryForObject(
                "SELECT response_expires_at FROM recommendation_request WHERE id = ?",
                LocalDateTime.class,
                saved.requestId()))
        .isEqualTo(
            LocalDateTime.ofInstant(schedule.drawAt().plus(Duration.ofDays(30)), ZoneOffset.UTC));
    assertThat(
            jdbc.queryForList(
                "SELECT CONCAT(metric, ':', delta) FROM usage_event ORDER BY metric", String.class))
        .containsExactly("RECOMMENDATION_SETS:5", "RECOMMENDATION_SUCCEEDED:1");
  }

  @Test
  @DisplayName("끝난 직후 다시 오면 GENERATION_BUSY, 1초 뒤에는 앞과 겹치지 않는 새 5세트다")
  void sequentialRecommendationsDoNotOverlap() {
    service.recommend(basic(1));

    assertThatThrownBy(() -> service.recommend(basic(2)))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.getCode()).isEqualTo(ErrorCode.GENERATION_BUSY));
    clock.set(T0.plusSeconds(1));
    service.recommend(basic(3));

    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM recommendation_seen", Long.class))
        .isEqualTo(10);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM recommendation_request", Long.class))
        .isEqualTo(2);
  }

  @Test
  @DisplayName("판매 마감이 지났거나 일정 버전이 다르면 ROUND_CHANGED이고 아무것도 저장하지 않으며 권한은 풀린다")
  void closedRoundSavesNothing() {
    assertThatThrownBy(
            () ->
                service.recommend(
                    new RecommendCommand(
                        subject, ROUND, 2, RecommendationCondition.none(), digest(1), digest(1))))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.getCode()).isEqualTo(ErrorCode.ROUND_CHANGED));
    jdbc.update(
        "UPDATE lotto_round SET sales_close_at = ? WHERE round_no = ?",
        LocalDateTime.ofInstant(rounds.dbNow().minusMillis(1), ZoneOffset.UTC),
        ROUND);
    clock.set(T0.plusSeconds(1));
    assertThatThrownBy(() -> service.recommend(basic(1)))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.getCode()).isEqualTo(ErrorCode.ROUND_CHANGED));

    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM recommendation_request", Long.class))
        .isZero();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM usage_event", Long.class)).isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT generation_lease_until IS NULL FROM actor_subject WHERE id = ?",
                Boolean.class,
                subject))
        .isTrue();
  }

  @Test
  @DisplayName("조건 추천 후보가 5개 미만이면 CANDIDATES_EXHAUSTED이고 아무것도 저장하지 않는다")
  void exhaustedCondition() {
    RecommendationCondition four =
        RecommendationCondition.of(
            List.of(1, 2, 3, 4, 5), IntStream.rangeClosed(6, 41).boxed().toList());

    assertThatThrownBy(
            () ->
                service.recommend(
                    new RecommendCommand(subject, ROUND, 1, four, digest(1), digest(1))))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.getCode()).isEqualTo(ErrorCode.CANDIDATES_EXHAUSTED));
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM recommendation_request", Long.class))
        .isZero();
  }

  @Test
  @DisplayName("조건 추천은 조건을 기록하고 필터 버전은 비운다")
  void conditionRecommendationRecordsCondition() {
    RecommendationCondition five =
        RecommendationCondition.of(
            List.of(1, 2, 3, 4, 5), IntStream.rangeClosed(6, 40).boxed().toList());

    service.recommend(new RecommendCommand(subject, ROUND, 1, five, digest(1), digest(1)));

    assertThat(
            jdbc.queryForMap(
                "SELECT generation_mode, filter_version, condition_json FROM recommendation_request"))
        .containsEntry("generation_mode", "CONDITION")
        .containsEntry("filter_version", null);
    assertThat(
            jdbc.queryForList("SELECT numbers_key FROM recommendation_seen", String.class).stream()
                .map(LottoNumbers::fromKey))
        .allSatisfy(n -> assertThat(n.toList()).startsWith(1, 2, 3, 4, 5));
  }

  @Test
  @DisplayName("과거 1등 비교 자료는 추천 회차보다 앞선 회차의 게시 결과이고 방금 게시한 결과도 바로 들어간다(T35)")
  void pastFirstPrizesBeforeRound() {
    rounds.register(
        new RoundSchedule(
            ROUND - 1,
            schedule.salesOpenAt().minus(Duration.ofDays(7)),
            schedule.salesOpenAt(),
            schedule.salesOpenAt().plusSeconds(60),
            1));
    rounds.register(
        new RoundSchedule(
            ROUND + 1,
            schedule.salesCloseAt(),
            schedule.salesCloseAt().plus(Duration.ofDays(7)),
            schedule.salesCloseAt().plus(Duration.ofDays(7)),
            1));
    PublishedFirstPrizes prizes = new PublishedFirstPrizes(jdbc);
    assertThat(prizes.before(ROUND)).isEmpty();

    publish(ROUND - 1, "03 11 19 27 36 44");
    publish(ROUND + 1, "01 02 03 04 05 06");

    assertThat(prizes.before(ROUND)).containsExactly(LottoNumbers.fromKey("03 11 19 27 36 44"));
    assertThat(prizes.before(ROUND + 2)).hasSize(2);
  }

  private void publish(int roundNo, String key) {
    jdbc.update(
        "INSERT INTO draw_result (public_id, round_no, revision, numbers_key, bonus_number,"
            + " semantic_hash, source_hash, state, fetched_at, published_at)"
            + " VALUES (UUID_TO_BIN(UUID()), ?, 1, ?, 45, ?, ?, 'VERIFIED', NOW(6), NOW(6))",
        roundNo,
        key,
        new byte[32],
        new byte[32]);
    jdbc.update(
        "UPDATE lotto_round SET published_result_id = (SELECT id FROM draw_result WHERE round_no"
            + " = ?), publication_version = publication_version + 1 WHERE round_no = ?",
        roundNo,
        roundNo);
  }

  private RecommendCommand basic(int key) {
    return new RecommendCommand(
        subject, ROUND, 1, RecommendationCondition.none(), digest(key), digest(key));
  }

  private static byte[] digest(int value) {
    byte[] b = new byte[32];
    b[0] = (byte) value;
    return b;
  }
}
