package com.lottery.lottery_service.recommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lottery.lottery_service.account.AccountStore;
import com.lottery.lottery_service.account.GuestCredentialStore;
import com.lottery.lottery_service.account.SubjectClaimStore;
import com.lottery.lottery_service.auth.OAuthProvider;
import com.lottery.lottery_service.common.number.LottoNumbers;
import com.lottery.lottery_service.recommendation.filter.GenerationFilterPipeline;
import com.lottery.lottery_service.support.MySqlContainerSupport;
import com.lottery.lottery_service.support.TestSecrets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
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

/** {@link RecommendationStore}의 이력 읽기와 저장을 MySQL에서 확인한다. */
class RecommendationStoreMySqlTest extends MySqlContainerSupport {

  private static final Instant T0 = Instant.parse("2026-11-08T01:00:00Z");
  private static final int ROUND = 1200;

  private static DataSource dataSource;

  private JdbcTemplate jdbc;
  private TransactionTemplate tx;
  private DataSourceTransactionManager tm;
  private RecommendationStore store;
  private long subject;

  @BeforeAll
  static void migrate() throws Exception {
    dataSource = migratedDatabase("recommendation_store");
  }

  @BeforeEach
  void setUp() {
    jdbc = new JdbcTemplate(dataSource);
    for (String table :
        List.of(
            "recommendation_seen",
            "recommendation_item",
            "recommendation_request",
            "lotto_record",
            "subject_claim",
            "guest_credential",
            "oauth_account",
            "actor_subject",
            "member",
            "lotto_round")) {
      jdbc.update("DELETE FROM " + table);
    }
    jdbc.update(
        "INSERT INTO lotto_round (round_no, sales_open_at, sales_close_at, draw_at,"
            + " schedule_version) VALUES (?, '2026-11-07 21:00:00', '2026-11-14 11:00:00',"
            + " '2026-11-14 11:35:00', 1)",
        ROUND);
    tm = new DataSourceTransactionManager(dataSource);
    tx = new TransactionTemplate(tm);
    store = new RecommendationStore(jdbc);
    subject = guest();
  }

  @Test
  @DisplayName("저장하면 요청 1개, 항목 5개, 이력 5개, 추천 표시가 있는 기록 5개가 생긴다")
  void saveCreatesEverything() {
    SavedRecommendation saved = save(subject, sets(1));

    assertThat(saved.recordIds()).hasSize(5).doesNotHaveDuplicates();
    assertThat(count("recommendation_item WHERE request_id = " + saved.requestId())).isEqualTo(5);
    assertThat(count("recommendation_seen")).isEqualTo(5);
    assertThat(count("lotto_record WHERE recommended_at IS NOT NULL AND purchased = FALSE"))
        .isEqualTo(5);
    assertThat(
            jdbc.queryForMap(
                "SELECT generation_mode, filter_version FROM recommendation_request WHERE id = ?",
                saved.requestId()))
        .containsEntry("generation_mode", "BASIC")
        .containsEntry("filter_version", "FILTER_V1");
    assertThat(store.seen(subject, ROUND)).containsExactlyInAnyOrderElementsOf(sets(1));
  }

  @Test
  @DisplayName("이미 직접 구매 등록한 조합이 나오면 기존 기록에 추천 표시만 더하고 구매 수는 그대로다")
  void existingPurchaseGetsRecommendationMark() {
    LottoNumbers bought = sets(1).get(0);
    jdbc.update(
        "INSERT INTO lotto_record (public_id, owner_subject_id, round_no, numbers_key, purchased,"
            + " purchased_at) VALUES (UUID_TO_BIN(UUID()), ?, ?, ?, TRUE, NOW(6))",
        subject,
        ROUND,
        bought.key());

    save(subject, sets(1));

    assertThat(count("lotto_record")).isEqualTo(5);
    assertThat(count("lotto_record WHERE purchased = TRUE")).isEqualTo(1);
    assertThat(
            count(
                "lotto_record WHERE numbers_key = '"
                    + bought.key()
                    + "' AND purchased = TRUE AND recommended_at IS NOT NULL"))
        .isEqualTo(1);
  }

  @Test
  @DisplayName("기록을 지우거나 구매를 취소해도 같은 회차 이력에는 남아 다시 나오지 않는다(T09)")
  void historySurvivesRecordDeletion() {
    save(subject, sets(1));
    jdbc.update("UPDATE lotto_record SET deleted_at = NOW(6)");

    assertThat(store.seen(subject, ROUND)).hasSize(5);
    assertThatThrownBy(() -> tx.execute(s -> save(subject, sets(1))))
        .isInstanceOf(RecommendationConflictException.class);
  }

  @Test
  @DisplayName("귀속한 비회원 주체가 받은 조합도 이력에 합친다")
  void claimedGuestHistoryIsMerged() {
    long guest = subject;
    save(guest, sets(1));
    Clock clock = Clock.fixed(T0, ZoneOffset.UTC);
    long member = new AccountStore(jdbc, tm, clock).login(OAuthProvider.KAKAO, "1").subjectId();
    new SubjectClaimStore(jdbc, tm, clock).claim(guest, member);

    assertThat(store.seen(member, ROUND)).containsExactlyInAnyOrderElementsOf(sets(1));
    assertThatThrownBy(() -> tx.execute(s -> save(member, sets(1))))
        .isInstanceOf(RecommendationConflictException.class);
    assertThat(count("recommendation_request")).isEqualTo(1);
  }

  @Test
  @DisplayName("겹치는 조합을 저장하려 하면 거부되고 그 트랜잭션의 쓰기는 하나도 남지 않는다")
  void conflictRollsBack() {
    save(subject, sets(1));
    List<LottoNumbers> overlapping =
        List.of(sets(2).get(0), sets(2).get(1), sets(2).get(2), sets(2).get(3), sets(1).get(4));

    assertThatThrownBy(() -> tx.execute(s -> save(subject, overlapping)))
        .isInstanceOf(RecommendationConflictException.class);
    assertThat(count("recommendation_request")).isEqualTo(1);
    assertThat(count("lotto_record")).isEqualTo(5);
  }

  @Test
  @DisplayName("같은 주체의 동시 저장 2건이 겹치면 주체 잠금 뒤 하나만 성공하고 중복은 0이다(T03 저장 부분)")
  void concurrentOverlappingSaves() throws Exception {
    CountDownLatch start = new CountDownLatch(1);
    List<CompletableFuture<Boolean>> calls =
        List.of(1, 1).stream()
            .map(
                seed ->
                    CompletableFuture.supplyAsync(
                        () -> {
                          try {
                            start.await();
                            tx.executeWithoutResult(
                                s -> {
                                  jdbc.queryForList(
                                      "SELECT id FROM actor_subject WHERE id = ? FOR UPDATE",
                                      subject);
                                  save(subject, sets(seed));
                                });
                            return true;
                          } catch (RecommendationConflictException e) {
                            return false;
                          } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return false;
                          }
                        }))
            .toList();
    start.countDown();

    long ok = 0;
    for (CompletableFuture<Boolean> c : calls) {
      ok += c.get(20, TimeUnit.SECONDS) ? 1 : 0;
    }
    assertThat(ok).isEqualTo(1);
    assertThat(count("recommendation_seen")).isEqualTo(5);
  }

  private SavedRecommendation save(long subjectId, List<LottoNumbers> sets) {
    return tx.execute(
        s ->
            store.save(
                new NewRecommendation(
                    subjectId,
                    ROUND,
                    sets,
                    T0,
                    new byte[32],
                    new byte[32],
                    1,
                    GenerationFilterPipeline.VERSION,
                    null,
                    T0.plus(Duration.ofDays(30)))));
  }

  private long guest() {
    return new GuestCredentialStore(jdbc, tm, Clock.fixed(T0, ZoneOffset.UTC), TestSecrets.keys())
        .issue()
        .subjectId();
  }

  private static List<LottoNumbers> sets(int base) {
    int b = base * 6;
    return List.of(
        LottoNumbers.of(1, 2, 3, 4, 5, b + 10),
        LottoNumbers.of(1, 2, 3, 4, 6, b + 10),
        LottoNumbers.of(1, 2, 3, 4, 7, b + 10),
        LottoNumbers.of(1, 2, 3, 4, 8, b + 10),
        LottoNumbers.of(1, 2, 3, 4, 9, b + 10));
  }

  private long count(String from) {
    return jdbc.queryForObject("SELECT COUNT(*) FROM " + from, Long.class);
  }
}
