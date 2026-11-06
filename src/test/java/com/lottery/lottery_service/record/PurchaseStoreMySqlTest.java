package com.lottery.lottery_service.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lottery.lottery_service.account.AccountStore;
import com.lottery.lottery_service.account.GuestCredentialStore;
import com.lottery.lottery_service.auth.OAuthProvider;
import com.lottery.lottery_service.common.error.BusinessException;
import com.lottery.lottery_service.common.error.ErrorCode;
import com.lottery.lottery_service.common.job.JobProperties;
import com.lottery.lottery_service.common.job.JobStore;
import com.lottery.lottery_service.common.number.LottoNumbers;
import com.lottery.lottery_service.round.RoundStore;
import com.lottery.lottery_service.round.domain.RoundSchedule;
import com.lottery.lottery_service.stats.UsageEventStore;
import com.lottery.lottery_service.support.MySqlContainerSupport;
import com.lottery.lottery_service.support.TestSecrets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 구매 등록과 취소를 MySQL에서 확인한다. */
class PurchaseStoreMySqlTest extends MySqlContainerSupport {

  private static final int ROUND = 1200;
  private static final LottoNumbers A = LottoNumbers.of(1, 2, 3, 4, 5, 6);
  private static final LottoNumbers B = LottoNumbers.of(7, 8, 9, 10, 11, 12);

  private static DataSource dataSource;

  private JdbcTemplate jdbc;
  private TransactionTemplate tx;
  private DataSourceTransactionManager tm;
  private RoundStore rounds;
  private PurchaseStore store;
  private long member;

  @BeforeAll
  static void migrate() throws Exception {
    dataSource = migratedDatabase("purchase_store");
  }

  @BeforeEach
  void setUp() {
    jdbc = new JdbcTemplate(dataSource);
    for (String table :
        List.of(
            "job",
            "usage_event",
            "lotto_record",
            "oauth_account",
            "guest_credential",
            "actor_subject",
            "member")) {
      jdbc.update("DELETE FROM " + table);
    }
    jdbc.update("UPDATE lotto_round SET published_result_id = NULL");
    jdbc.update("DELETE FROM draw_result");
    jdbc.update("DELETE FROM lotto_round");
    tm = new DataSourceTransactionManager(dataSource);
    tx = new TransactionTemplate(tm);
    rounds = new RoundStore(jdbc, tm);
    Instant now = rounds.dbNow();
    rounds.register(
        new RoundSchedule(
            ROUND, now.minus(Duration.ofDays(1)), now.plusSeconds(3600), now.plusSeconds(7200), 1));
    Clock clock = Clock.fixed(Instant.parse("2026-11-08T01:00:00Z"), ZoneOffset.UTC);
    store =
        new PurchaseStore(
            jdbc,
            rounds,
            new UsageEventStore(jdbc),
            new JobStore(jdbc, tm, clock, new JobProperties(null, null)));
    member = new AccountStore(jdbc, tm, clock).login(OAuthProvider.KAKAO, "m").subjectId();
  }

  @Test
  @DisplayName("새 조합은 CREATED, 요청 안의 같은 조합은 하나로 합치고, 다시 등록하면 ALREADY_REGISTERED")
  void createAndAlready() {
    PurchaseResult first = register(List.of(A, B, LottoNumbers.of(6, 5, 4, 3, 2, 1)));
    PurchaseResult second = register(List.of(A));

    assertThat(first.items())
        .extracting(PurchaseItem::outcome)
        .containsExactly(PurchaseOutcome.CREATED, PurchaseOutcome.CREATED);
    assertThat(second.items().get(0).outcome()).isEqualTo(PurchaseOutcome.ALREADY_REGISTERED);
    assertThat(second.items().get(0).recordPublicId())
        .isEqualTo(first.items().get(0).recordPublicId());
    assertThat(count("lotto_record")).isEqualTo(2);
    assertThat(
            jdbc.queryForObject(
                "SELECT SUM(delta) FROM usage_event WHERE metric = 'PURCHASE_REGISTERED'",
                Long.class))
        .isEqualTo(2);
    assertThat(first.afterSalesClose()).isFalse();
  }

  @Test
  @DisplayName("추천만 받은 조합을 등록하면 새 기록 없이 구매 표시만 붙는다(CONVERTED)")
  void convertRecommendation() {
    jdbc.update(
        "INSERT INTO lotto_record (public_id, owner_subject_id, round_no, numbers_key,"
            + " recommended_at) VALUES (UUID_TO_BIN(UUID()), ?, ?, ?, NOW(6))",
        member,
        ROUND,
        A.key());

    PurchaseResult result = register(List.of(A));

    assertThat(result.items().get(0).outcome()).isEqualTo(PurchaseOutcome.CONVERTED);
    assertThat(count("lotto_record WHERE purchased = TRUE AND recommended_at IS NOT NULL"))
        .isEqualTo(1);
  }

  @Test
  @DisplayName("비회원은 등록할 수 없고, 0개나 101개는 VALIDATION_FAILED")
  void validation() {
    long guest =
        new GuestCredentialStore(jdbc, tm, Clock.systemUTC(), TestSecrets.keys())
            .issue()
            .subjectId();
    assertCode(
        () -> tx.execute(s -> store.register(guest, ROUND, List.of(A))), ErrorCode.FORBIDDEN);
    assertCode(() -> register(List.of()), ErrorCode.VALIDATION_FAILED);
    assertCode(() -> register(distinctSets(101, 0)), ErrorCode.VALIDATION_FAILED);
  }

  @Test
  @DisplayName("같은 회차에 100개 등록 뒤 1개 더 등록하면 둘 다 성공해 누적 101(T33)")
  void hundredThenOne() {
    register(distinctSets(100, 0));
    register(distinctSets(1, 100));

    assertThat(count("lotto_record")).isEqualTo(101);
  }

  @Test
  @DisplayName("추천 출신 기록의 취소는 구매 표시만 풀고, 직접 등록 기록의 취소는 숨긴다")
  void cancel() {
    jdbc.update(
        "INSERT INTO lotto_record (public_id, owner_subject_id, round_no, numbers_key,"
            + " recommended_at) VALUES (UUID_TO_BIN(UUID()), ?, ?, ?, NOW(6))",
        member,
        ROUND,
        A.key());
    PurchaseResult result = register(List.of(A, B));
    UUID recommended = result.items().get(0).recordPublicId();
    UUID direct = result.items().get(1).recordPublicId();

    cancel(recommended, 1);
    cancel(direct, 0);

    assertThat(count("lotto_record WHERE deleted_at IS NULL AND purchased = FALSE")).isEqualTo(1);
    assertThat(count("lotto_record WHERE deleted_at IS NOT NULL")).isEqualTo(1);
    assertCode(() -> cancel(direct, 1), ErrorCode.NOT_FOUND);
  }

  @Test
  @DisplayName("지운 직접 등록 기록을 다시 등록하면 행을 되살리고 created_at과 purchased_at을 다시 찍는다(D-041, T50 일부)")
  void reRegisterRevives() throws Exception {
    UUID id = register(List.of(B)).items().get(0).recordPublicId();
    LocalDateTime createdBefore = createdAt(id);
    cancel(id, 0);
    Thread.sleep(5);

    PurchaseResult again = register(List.of(B));

    assertThat(again.items().get(0)).isEqualTo(new PurchaseItem(B, id, PurchaseOutcome.CREATED));
    assertThat(createdAt(id)).isAfter(createdBefore);
    assertThat(count("lotto_record WHERE deleted_at IS NULL AND purchased = TRUE")).isEqualTo(1);
  }

  @Test
  @DisplayName("화면이 본 버전이 다르면 VERSION_CONFLICT와 현재 버전. 다시 구매한 뒤 옛 취소는 새 구매를 취소하지 않는다(T11)")
  void staleCancel() {
    UUID id = register(List.of(B)).items().get(0).recordPublicId();
    cancel(id, 0);
    register(List.of(B));

    assertThatThrownBy(() -> cancel(id, 0))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> {
              assertThat(e.getCode()).isEqualTo(ErrorCode.VERSION_CONFLICT);
              assertThat(e.getDetails().currentVersion()).isEqualTo("2");
            });
    assertThat(count("lotto_record WHERE purchased = TRUE AND deleted_at IS NULL")).isEqualTo(1);
  }

  @Test
  @DisplayName("같은 조합을 동시에 등록해도 기록은 1개이고 나머지는 이미 등록이다(T10)")
  void concurrentRegister() throws Exception {
    CountDownLatch start = new CountDownLatch(1);
    List<CompletableFuture<PurchaseOutcome>> calls = new ArrayList<>();
    for (int i = 0; i < 3; i++) {
      calls.add(
          CompletableFuture.supplyAsync(
              () -> {
                try {
                  start.await();
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                }
                return register(List.of(A)).items().get(0).outcome();
              }));
    }
    start.countDown();

    List<PurchaseOutcome> outcomes = new ArrayList<>();
    for (CompletableFuture<PurchaseOutcome> c : calls) {
      outcomes.add(c.get(20, TimeUnit.SECONDS));
    }
    assertThat(outcomes)
        .containsExactlyInAnyOrder(
            PurchaseOutcome.CREATED,
            PurchaseOutcome.ALREADY_REGISTERED,
            PurchaseOutcome.ALREADY_REGISTERED);
    assertThat(count("lotto_record")).isEqualTo(1);
  }

  @Test
  @DisplayName("마감 뒤 등록은 결과에 표시하고, 결과가 게시된 회차면 바뀐 기록마다 판정 job을 등록한다")
  void afterCloseAndPublished() {
    jdbc.update(
        "UPDATE lotto_round SET sales_close_at = ? WHERE round_no = ?",
        LocalDateTime.ofInstant(rounds.dbNow().minusSeconds(1), ZoneOffset.UTC),
        ROUND);
    jdbc.update(
        "INSERT INTO draw_result (public_id, round_no, revision, numbers_key, bonus_number,"
            + " semantic_hash, source_hash, state, fetched_at, published_at) VALUES"
            + " (UUID_TO_BIN(UUID()), ?, 1, '01 02 03 04 05 06', 7, ?, ?, 'VERIFIED', NOW(6),"
            + " NOW(6))",
        ROUND,
        new byte[32],
        new byte[32]);
    jdbc.update(
        "UPDATE lotto_round SET published_result_id = (SELECT id FROM draw_result) WHERE"
            + " round_no = ?",
        ROUND);

    PurchaseResult result = register(List.of(A, B));
    register(List.of(A));

    assertThat(result.afterSalesClose()).isTrue();
    assertThat(count("job WHERE job_type = 'EVALUATE_RECORD'")).isEqualTo(2);
  }

  private PurchaseResult register(List<LottoNumbers> sets) {
    return tx.execute(s -> store.register(member, ROUND, sets));
  }

  private void cancel(UUID record, long expectedVersion) {
    tx.executeWithoutResult(s -> store.cancel(member, record, expectedVersion));
  }

  private LocalDateTime createdAt(UUID id) {
    return jdbc.queryForObject(
        "SELECT created_at FROM lotto_record WHERE public_id = UUID_TO_BIN(?)",
        LocalDateTime.class,
        id.toString());
  }

  private long count(String from) {
    return jdbc.queryForObject("SELECT COUNT(*) FROM " + from, Long.class);
  }

  private static List<LottoNumbers> distinctSets(int n, int offset) {
    List<LottoNumbers> all = new ArrayList<>();
    for (int x = 4; x <= 45 && all.size() < offset + n; x++) {
      for (int y = x + 1; y <= 45 && all.size() < offset + n; y++) {
        for (int z = y + 1; z <= 45 && all.size() < offset + n; z++) {
          all.add(LottoNumbers.of(1, 2, 3, x, y, z));
        }
      }
    }
    return all.subList(offset, offset + n);
  }

  private static void assertCode(Runnable call, ErrorCode code) {
    assertThatThrownBy(call::run)
        .isInstanceOfSatisfying(
            BusinessException.class, e -> assertThat(e.getCode()).isEqualTo(code));
  }
}
