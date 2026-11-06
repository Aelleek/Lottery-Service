package com.lottery.lottery_service.recommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lottery.lottery_service.account.GuestCredential;
import com.lottery.lottery_service.account.GuestCredentialStore;
import com.lottery.lottery_service.common.command.CommandOperationStore;
import com.lottery.lottery_service.common.command.CommandTokenCodec;
import com.lottery.lottery_service.common.command.OperationQuery;
import com.lottery.lottery_service.common.db.DbValues;
import com.lottery.lottery_service.common.error.BusinessException;
import com.lottery.lottery_service.common.error.ErrorCode;
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
import java.util.List;
import java.util.SplittableRandom;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/** 멱등 추천 명령(같은 요청 1회 처리, 원본 만료)을 MySQL에서 확인한다. */
class RecommendationCommandServiceMySqlTest extends MySqlContainerSupport {

  private static final Instant T0 = Instant.parse("2026-11-08T01:00:00Z");
  private static final int ROUND = 1200;

  private static DataSource dataSource;

  private final MutableClock clock = new MutableClock(T0);
  private JdbcTemplate jdbc;
  private RoundStore rounds;
  private RecommendationService service;
  private RoundSchedule schedule;
  private long subject;
  private UUID subjectPublicId;
  private RecommendationCommandService commands;
  private CommandTokenCodec codec;

  @BeforeAll
  static void migrate() throws Exception {
    dataSource = migratedDatabase("recommendation_command");
  }

  @BeforeEach
  void setUp() {
    jdbc = new JdbcTemplate(dataSource);
    for (String table :
        List.of(
            "usage_event",
            "command_operation",
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
    GuestCredential guest = new GuestCredentialStore(jdbc, tm, clock, TestSecrets.keys()).issue();
    subject = guest.subjectId();
    subjectPublicId = guest.subjectPublicId();
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
    codec = new CommandTokenCodec(TestSecrets.keys(), clock);
    commands =
        new RecommendationCommandService(
            codec, new CommandOperationStore(jdbc, tm, clock), service, jdbc);
  }

  @Test
  @DisplayName("같은 토큰·키·본문을 다시 보내면 처음 5세트를 돌려주고 추천과 사용량은 늘지 않는다(T04)")
  void sameRequestReturnsSameSets() {
    String token = codec.issue(subjectPublicId, "RECOMMEND", ROUND, 1L);
    UUID key = UUID.randomUUID();

    RecommendationView first = recommend(token, key, RecommendationCondition.none(), 1);
    RecommendationView again = recommend(token, key, RecommendationCondition.none(), 1);

    assertThat(again).isEqualTo(first);
    assertThat(count("recommendation_request")).isEqualTo(1);
    assertThat(count("usage_event")).isEqualTo(2);
  }

  @Test
  @DisplayName("명령 조회로 끝난 추천을 다시 읽으면 처음 5세트를 돌려준다")
  void operationLookupReturnsSameSets() {
    String token = codec.issue(subjectPublicId, "RECOMMEND", ROUND, 1L);
    RecommendationView first =
        recommend(token, UUID.randomUUID(), RecommendationCondition.none(), 1);
    UUID operation =
        DbValues.uuid(jdbc.queryForObject("SELECT public_id FROM command_operation", byte[].class));

    assertThat(new OperationQuery(jdbc, List.of(commands)).find(subject, operation))
        .isEqualTo(new OperationQuery.Outcome.Done(operation, first));
  }

  @Test
  @DisplayName("새 키는 새 추천이고, 같은 키에 다른 본문은 IDEMPOTENCY_CONFLICT")
  void newKeyAndConflict() {
    String token = codec.issue(subjectPublicId, "RECOMMEND", ROUND, 1L);
    UUID key = UUID.randomUUID();
    RecommendationView first = recommend(token, key, RecommendationCondition.none(), 1);
    clock.set(T0.plusSeconds(1));

    RecommendationView second =
        recommend(token, UUID.randomUUID(), RecommendationCondition.none(), 1);
    assertThat(second.sets()).doesNotContainAnyElementsOf(first.sets());
    assertCode(
        () -> recommend(token, key, RecommendationCondition.of(List.of(7), List.of()), 1),
        ErrorCode.IDEMPOTENCY_CONFLICT);
  }

  @Test
  @DisplayName("원본이 정리된 뒤 같은 요청을 다시 보내면 410 RESPONSE_EXPIRED이고 새 번호를 만들지 않는다(T05)")
  void expiredOriginal() {
    String token = codec.issue(subjectPublicId, "RECOMMEND", ROUND, 1L);
    UUID key = UUID.randomUUID();
    recommend(token, key, RecommendationCondition.none(), 1);
    jdbc.update("DELETE FROM recommendation_seen");
    jdbc.update("DELETE FROM recommendation_item");
    jdbc.update("DELETE FROM recommendation_request");
    clock.set(T0.plus(CommandTokenCodec.VALIDITY));

    assertCode(
        () -> recommend(token, key, RecommendationCondition.none(), 1), ErrorCode.RESPONSE_EXPIRED);
    assertThat(count("recommendation_request")).isZero();
  }

  @Test
  @DisplayName("확정 실패한 요청을 다시 보내면 같은 실패이고, 토큰과 다른 회차·버전은 FORBIDDEN")
  void failedAndMismatched() {
    String stale = codec.issue(subjectPublicId, "RECOMMEND", ROUND, 2L);
    UUID key = UUID.randomUUID();

    assertCode(
        () ->
            commands.recommend(
                subject, subjectPublicId, stale, key, RecommendationCondition.none(), ROUND, 2L),
        ErrorCode.ROUND_CHANGED);
    clock.set(T0.plusSeconds(1));
    assertCode(
        () ->
            commands.recommend(
                subject, subjectPublicId, stale, key, RecommendationCondition.none(), ROUND, 2L),
        ErrorCode.ROUND_CHANGED);
    assertCode(
        () -> recommend(stale, UUID.randomUUID(), RecommendationCondition.none(), 1),
        ErrorCode.FORBIDDEN);
    assertThat(
            jdbc.queryForObject(
                "SELECT state FROM command_operation WHERE client_key = ?",
                String.class,
                DbValues.bytes(key)))
        .isEqualTo("FAILED");
  }

  private RecommendationView recommend(
      String token, UUID key, RecommendationCondition condition, long scheduleVersion) {
    return commands.recommend(
        subject, subjectPublicId, token, key, condition, ROUND, scheduleVersion);
  }

  private long count(String table) {
    return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
  }

  private static void assertCode(Runnable call, ErrorCode code) {
    assertThatThrownBy(call::run)
        .isInstanceOfSatisfying(
            BusinessException.class, e -> assertThat(e.getCode()).isEqualTo(code));
  }
}
