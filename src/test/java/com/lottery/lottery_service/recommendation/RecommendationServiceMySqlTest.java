package com.lottery.lottery_service.recommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.lottery.lottery_service.account.AccountStore;
import com.lottery.lottery_service.account.GuestCredentialStore;
import com.lottery.lottery_service.auth.OAuthProvider;
import com.lottery.lottery_service.common.error.BusinessException;
import com.lottery.lottery_service.common.error.ErrorCode;
import com.lottery.lottery_service.common.job.JobProperties;
import com.lottery.lottery_service.common.job.JobStore;
import com.lottery.lottery_service.common.number.LottoNumbers;
import com.lottery.lottery_service.recommendation.filter.DifferenceKindsFilter;
import com.lottery.lottery_service.recommendation.filter.EndDigitSumFilter;
import com.lottery.lottery_service.recommendation.filter.FilterContext;
import com.lottery.lottery_service.recommendation.filter.GenerationFilterPipeline;
import com.lottery.lottery_service.recommendation.filter.LowHighFilter;
import com.lottery.lottery_service.recommendation.filter.OddEvenFilter;
import com.lottery.lottery_service.recommendation.filter.PastFirstPrizeFilter;
import com.lottery.lottery_service.recommendation.filter.SameEndDigitFilter;
import com.lottery.lottery_service.recommendation.filter.ThreeZoneFilter;
import com.lottery.lottery_service.recommendation.generator.RecommendationCondition;
import com.lottery.lottery_service.recommendation.generator.RecommendationGenerator;
import com.lottery.lottery_service.record.PurchaseStore;
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
import java.util.Map;
import java.util.SplittableRandom;
import java.util.UUID;
import java.util.stream.IntStream;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

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

  private DataSourceTransactionManager tm;
  private GenerationFilterPipeline filters;

  @BeforeAll
  static void migrate() throws Exception {
    dataSource = migratedDatabase("recommendation_service");
  }

  @BeforeEach
  void setUp() {
    jdbc = new JdbcTemplate(dataSource);
    for (String table :
        List.of(
            "job",
            "usage_event",
            "recommendation_seen",
            "recommendation_item",
            "recommendation_request",
            "lotto_record",
            "subject_claim",
            "guest_credential",
            "oauth_account",
            "actor_subject",
            "member")) {
      jdbc.update("DELETE FROM " + table);
    }
    jdbc.update("UPDATE lotto_round SET published_result_id = NULL");
    jdbc.update("DELETE FROM draw_result");
    jdbc.update("DELETE FROM lotto_round");
    DataSourceTransactionManager tm = new DataSourceTransactionManager(dataSource);
    this.tm = tm;
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
    filters =
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
    serviceWithHook(() -> {}).recommend(basic(3));

    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM recommendation_seen", Long.class))
        .isEqualTo(10);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM recommendation_request", Long.class))
        .isEqualTo(2);
  }

  @Test
  @DisplayName("판매 마감이 지났거나 일정 버전이 다르면 ROUND_CHANGED이고 추천은 저장하지 않으며(실패 사건만 남김) 권한은 풀린다")
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
    assertThat(
            jdbc.queryForList(
                "SELECT metric FROM usage_event ORDER BY occurred_at, metric", String.class))
        .containsExactly("RECOMMENDATION_FAILED", "RECOMMENDATION_FAILED");
    assertThat(
            jdbc.queryForObject(
                "SELECT generation_lease_until IS NULL FROM actor_subject WHERE id = ?",
                Boolean.class,
                subject))
        .isTrue();
  }

  @Test
  @DisplayName("시간 안에 못 만들면 후보 부족과 다른 GENERATION_TIMEOUT(503, 다시 시도 가능)이고 권한을 풀며 아무것도 저장하지 않는다")
  void generationTimeout() {
    long[] ticks = {0};
    RecommendationService slow =
        new RecommendationService(
            new GenerationLeaseStore(jdbc, tm, clock),
            new RecommendationStore(jdbc),
            rounds,
            new PublishedFirstPrizes(jdbc),
            filters,
            new RecommendationGenerator(
                new SplittableRandom(5), () -> ticks[0] += Duration.ofSeconds(10).toNanos()),
            new UsageEventStore(jdbc),
            tm);

    assertThatThrownBy(() -> slow.recommend(basic(1)))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> {
              assertThat(e.getCode()).isEqualTo(ErrorCode.GENERATION_TIMEOUT);
              assertThat(e.getCode().status().value()).isEqualTo(503);
              assertThat(e.isRetryable()).isTrue();
            });
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM recommendation_request", Long.class))
        .isZero();
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
    assertThat(
            jdbc.queryForMap(
                "SELECT generation_fence, generation_lease_until, next_generation_at"
                    + " FROM actor_subject WHERE id = ?",
                subject))
        .containsEntry("generation_fence", 0L)
        .containsEntry("generation_lease_until", null)
        .containsEntry("next_generation_at", null);
    assertThat(service.recommend(basic(2)).recordIds()).hasSize(5);
  }

  @Test
  @DisplayName("저장 때 이력이 겹치면 다시 만들어 저장하고, 계속 겹치면 GENERATION_TIMEOUT이다")
  void regeneratesOnConflict() {
    RecommendationStore real = new RecommendationStore(jdbc);
    RecommendationStore flaky = spy(real);
    doThrow(new RecommendationConflictException(subject, ROUND))
        .doCallRealMethod()
        .when(flaky)
        .save(any());
    RecommendationService retrying = serviceWith(flaky);

    assertThat(retrying.recommend(basic(3)).recordIds()).hasSize(5);
    verify(flaky, times(2)).save(any());

    clock.set(clock.instant().plusSeconds(2));
    RecommendationStore always = spy(real);
    doThrow(new RecommendationConflictException(subject, ROUND)).when(always).save(any());
    assertThatThrownBy(() -> serviceWith(always).recommend(basic(4)))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.getCode()).isEqualTo(ErrorCode.GENERATION_TIMEOUT));
  }

  @Test
  @DisplayName("조건을 바꾸거나 구매를 등록했다 취소한 뒤 다시 추천해도 같은 회차의 이전 조합은 계속 빠진다(T09)")
  void earlierSetsStayExcludedAfterConditionChangeAndCancel() {
    long memberSubject =
        new AccountStore(jdbc, tm, clock).login(OAuthProvider.KAKAO, "t09").subjectId();
    PurchaseStore purchases =
        new PurchaseStore(
            jdbc,
            rounds,
            new UsageEventStore(jdbc),
            new JobStore(jdbc, tm, clock, new JobProperties(null, null)));
    TransactionTemplate tx = new TransactionTemplate(tm);

    service.recommend(
        new RecommendCommand(
            memberSubject, ROUND, 1, RecommendationCondition.none(), digest(1), digest(1)));
    List<String> first = keysOf(memberSubject);
    LottoNumbers bought = LottoNumbers.fromKey(first.get(0));
    tx.executeWithoutResult(s -> purchases.register(memberSubject, ROUND, List.of(bought)));
    Map<String, Object> record =
        jdbc.queryForMap(
            "SELECT BIN_TO_UUID(public_id) AS id, version FROM lotto_record"
                + " WHERE owner_subject_id = ? AND numbers_key = ?",
            memberSubject,
            bought.key());
    tx.executeWithoutResult(
        s ->
            purchases.cancel(
                memberSubject,
                UUID.fromString((String) record.get("id")),
                ((Number) record.get("version")).longValue()));

    clock.set(T0.plusSeconds(1));
    service.recommend(
        new RecommendCommand(
            memberSubject,
            ROUND,
            1,
            RecommendationCondition.of(List.of(bought.toList().get(0)), List.of()),
            digest(2),
            digest(2)));
    clock.set(T0.plusSeconds(2));
    serviceWithHook(() -> {})
        .recommend(
            new RecommendCommand(
                memberSubject, ROUND, 1, RecommendationCondition.none(), digest(3), digest(3)));

    List<String> seen =
        jdbc.queryForList(
            "SELECT numbers_key FROM recommendation_seen WHERE subject_id = ?",
            String.class,
            memberSubject);
    assertThat(seen).hasSize(15).containsAll(first);
    assertThat(
            jdbc.queryForList(
                "SELECT i.numbers_key FROM recommendation_item i JOIN recommendation_request r"
                    + " ON r.id = i.request_id WHERE r.subject_id = ?",
                String.class,
                memberSubject))
        .hasSize(15)
        .doesNotHaveDuplicates()
        .containsAll(first);
  }

  @Test
  @DisplayName("비회원이 추천받은 뒤 회원에 귀속되면 그 회원의 다음 추천은 비회원 이력까지 합쳐 빼고, 기록은 각 주체에 따로 남는다(T13)")
  void claimedGuestHistoryExcludedForMember() {
    service.recommend(basic(1));
    List<String> guestSets = keysOf(subject);
    long memberSubject =
        new AccountStore(jdbc, tm, clock).login(OAuthProvider.KAKAO, "t13").subjectId();
    new com.lottery.lottery_service.account.SubjectClaimStore(jdbc, tm, clock)
        .claim(subject, memberSubject);

    clock.set(T0.plusSeconds(1));
    serviceWithHook(() -> {})
        .recommend(
            new RecommendCommand(
                memberSubject, ROUND, 1, RecommendationCondition.none(), digest(2), digest(2)));

    List<String> memberSets = keysOf(memberSubject);
    assertThat(memberSets).hasSize(5).doesNotContainAnyElementsOf(guestSets);
    assertThat(keysOf(subject)).containsExactlyElementsOf(guestSets);
  }

  @Test
  @DisplayName("두 회원이 같은 조합을 추천받아도 둘 다 저장된다. 중복 제외는 주체마다다(T08)")
  void twoMembersMayReceiveSameSets() {
    AccountStore accounts = new AccountStore(jdbc, tm, clock);
    long first = accounts.login(OAuthProvider.KAKAO, "t08-a").subjectId();
    long second = accounts.login(OAuthProvider.KAKAO, "t08-b").subjectId();

    serviceWithHook(() -> {})
        .recommend(
            new RecommendCommand(
                first, ROUND, 1, RecommendationCondition.none(), digest(1), digest(1)));
    serviceWithHook(() -> {})
        .recommend(
            new RecommendCommand(
                second, ROUND, 1, RecommendationCondition.none(), digest(2), digest(2)));

    assertThat(keysOf(second)).hasSize(5).containsExactlyInAnyOrderElementsOf(keysOf(first));
  }

  @Test
  @DisplayName("회원과 비회원의 기본 추천 1,000회(각 500회)의 모든 세트가 필터 7개를 통과하고 요청마다 필터 버전이 남는다(T34)")
  void thousandBasicRecommendationsPassFilters() {
    long memberSubject =
        new AccountStore(jdbc, tm, clock).login(OAuthProvider.KAKAO, "t34").subjectId();
    for (int i = 0; i < 500; i++) {
      clock.set(T0.plusSeconds(i));
      service.recommend(
          new RecommendCommand(
              memberSubject,
              ROUND,
              1,
              RecommendationCondition.none(),
              digest(10_000 + i),
              digest(10_000 + i)));
      service.recommend(basic(20_000 + i));
    }

    FilterContext context = new FilterContext(ROUND, java.util.Set.of());
    List<String> keys =
        jdbc.queryForList("SELECT numbers_key FROM recommendation_item", String.class);
    assertThat(keys).hasSize(5_000);
    assertThat(keys)
        .allSatisfy(
            k -> assertThat(filters.firstFailure(LottoNumbers.fromKey(k), context)).isEmpty());
    assertThat(
            jdbc.queryForList(
                "SELECT DISTINCT CONCAT(generation_mode, ':', COALESCE(filter_version, '-'))"
                    + " FROM recommendation_request",
                String.class))
        .singleElement()
        .asString()
        .startsWith("BASIC:")
        .doesNotEndWith(":-");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM recommendation_request", Long.class))
        .isEqualTo(1_000);
  }

  private List<String> keysOf(long subjectId) {
    return jdbc.queryForList(
        "SELECT numbers_key FROM lotto_record WHERE owner_subject_id = ? ORDER BY id",
        String.class,
        subjectId);
  }

  private RecommendationService serviceWith(RecommendationStore store) {
    return new RecommendationService(
        new GenerationLeaseStore(jdbc, tm, clock),
        store,
        rounds,
        new PublishedFirstPrizes(jdbc),
        filters,
        new RecommendationGenerator(new SplittableRandom(9), System::nanoTime),
        new UsageEventStore(jdbc),
        tm);
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

  @Test
  @DisplayName("생성하는 동안 그 비회원이 로그인으로 귀속되면 저장하지 않는다(05 4절 3번)")
  void claimDuringGenerationSupersedes() {
    long member =
        new com.lottery.lottery_service.account.AccountStore(jdbc, tm, clock)
            .login(com.lottery.lottery_service.auth.OAuthProvider.KAKAO, "claimer")
            .subjectId();
    java.util.concurrent.atomic.AtomicBoolean claimed =
        new java.util.concurrent.atomic.AtomicBoolean();
    RecommendationService racing =
        serviceWithHook(
            () -> {
              if (claimed.compareAndSet(false, true)) {
                new com.lottery.lottery_service.account.SubjectClaimStore(jdbc, tm, clock)
                    .claim(subject, member);
              }
            });

    org.assertj.core.api.Assertions.assertThatThrownBy(() -> racing.recommend(basic(7)))
        .isInstanceOf(GenerationSupersededException.class);
    assertThat(claimed).isTrue();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM recommendation_request", Long.class))
        .isZero();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lotto_record", Long.class)).isZero();
  }

  @Test
  @DisplayName("마감 전에 계산을 시작했어도 저장할 때 마감이 지났으면 ROUND_CHANGED이고 추천·기록은 저장하지 않는다(T06)")
  void closeDuringGenerationSavesNothing() {
    java.util.concurrent.atomic.AtomicBoolean closed =
        new java.util.concurrent.atomic.AtomicBoolean();
    RecommendationService racing =
        serviceWithHook(
            () -> {
              if (closed.compareAndSet(false, true)) {
                jdbc.update(
                    "UPDATE lotto_round SET sales_close_at = ? WHERE round_no = ?",
                    LocalDateTime.ofInstant(rounds.dbNow().minusMillis(1), ZoneOffset.UTC),
                    ROUND);
              }
            });

    assertThatThrownBy(() -> racing.recommend(basic(8)))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.getCode()).isEqualTo(ErrorCode.ROUND_CHANGED));
    assertThat(closed).isTrue();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM recommendation_request", Long.class))
        .isZero();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lotto_record", Long.class)).isZero();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM recommendation_seen", Long.class))
        .isZero();
  }

  @Test
  @DisplayName("생성하는 동안 그 회원이 탈퇴를 접수하면 저장하지 않는다(05 11절 3번)")
  void withdrawalDuringGenerationSupersedes() {
    com.lottery.lottery_service.account.MemberLogin login =
        new com.lottery.lottery_service.account.AccountStore(jdbc, tm, clock)
            .login(com.lottery.lottery_service.auth.OAuthProvider.KAKAO, "leaver");
    java.util.concurrent.atomic.AtomicBoolean blocked =
        new java.util.concurrent.atomic.AtomicBoolean();
    RecommendationService racing =
        serviceWithHook(
            () -> {
              if (blocked.compareAndSet(false, true)) {
                new com.lottery.lottery_service.account.withdrawal.WithdrawalService(
                        jdbc,
                        tm,
                        new com.lottery.lottery_service.common.job.JobStore(
                            jdbc,
                            tm,
                            clock,
                            new com.lottery.lottery_service.common.job.JobProperties(null, null)),
                        clock)
                    .block(login.memberId());
              }
            });
    RecommendCommand command =
        new RecommendCommand(
            login.subjectId(), ROUND, 1, RecommendationCondition.none(), digest(8), digest(8));

    org.assertj.core.api.Assertions.assertThatThrownBy(() -> racing.recommend(command))
        .isInstanceOf(GenerationSupersededException.class);
    assertThat(blocked).isTrue();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM recommendation_request", Long.class))
        .isZero();
  }

  private RecommendationService serviceWithHook(Runnable duringGeneration) {
    return new RecommendationService(
        new GenerationLeaseStore(jdbc, tm, clock),
        new RecommendationStore(jdbc),
        rounds,
        new PublishedFirstPrizes(jdbc),
        filters,
        new RecommendationGenerator(
            new SplittableRandom(5),
            () -> {
              duringGeneration.run();
              return System.nanoTime();
            }),
        new UsageEventStore(jdbc),
        tm);
  }

  private RecommendCommand basic(int key) {
    return new RecommendCommand(
        subject, ROUND, 1, RecommendationCondition.none(), digest(key), digest(key));
  }

  private static byte[] digest(int value) {
    byte[] b = new byte[32];
    b[0] = (byte) value;
    b[1] = (byte) (value >>> 8);
    b[2] = (byte) (value >>> 16);
    return b;
  }
}
