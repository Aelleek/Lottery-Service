package com.lottery.lottery_service.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.lottery.lottery_service.account.AccountStore;
import com.lottery.lottery_service.auth.OAuthProvider;
import com.lottery.lottery_service.common.job.JobProperties;
import com.lottery.lottery_service.common.job.JobStore;
import com.lottery.lottery_service.common.number.LottoNumber;
import com.lottery.lottery_service.common.number.LottoNumbers;
import com.lottery.lottery_service.notification.budget.BudgetProperties;
import com.lottery.lottery_service.notification.budget.MessageBudgetStore;
import com.lottery.lottery_service.result.domain.WinningNumbers;
import com.lottery.lottery_service.result.evaluate.EvaluationStore;
import com.lottery.lottery_service.result.publish.PrizeProperties;
import com.lottery.lottery_service.result.publish.ResultPublisher;
import com.lottery.lottery_service.result.source.OfficialDraw;
import com.lottery.lottery_service.support.MutableClock;
import com.lottery.lottery_service.support.MySqlContainerSupport;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 결과 알림 메시지 상태와 비용을 MySQL에서 확인한다. */
class ResultMessageStoreMySqlTest extends MySqlContainerSupport {

  private static final int ROUND = 1200;

  private static DataSource dataSource;

  private final MutableClock clock = new MutableClock(Instant.parse("2026-11-14T12:30:00Z"));
  private JdbcTemplate jdbc;
  private TransactionTemplate tx;
  private ResultMessageStore store;
  private MessageBudgetStore budget;
  private long fourthSubject;
  private long fourthMember;
  private long thirdMember;

  @BeforeAll
  static void migrate() throws Exception {
    dataSource = migratedDatabase("result_message");
  }

  @BeforeEach
  void setUp() {
    jdbc = new JdbcTemplate(dataSource);
    for (String t :
        List.of(
            "delivery_message",
            "message_budget",
            "member_contact",
            "consent_event",
            "winning_evaluation",
            "job",
            "lotto_record",
            "oauth_account",
            "actor_subject",
            "member")) {
      jdbc.update("DELETE FROM " + t);
    }
    jdbc.update("UPDATE lotto_round SET published_result_id = NULL");
    jdbc.update("DELETE FROM draw_prize");
    jdbc.update("DELETE FROM draw_result");
    jdbc.update("DELETE FROM lotto_round");
    jdbc.update(
        "INSERT INTO lotto_round (round_no, sales_open_at, sales_close_at, draw_at, schedule_version,"
            + " cutoff_state, cutoff_sealed_at, cutoff_close_at) VALUES (?, '2026-11-07 21:00:00',"
            + " '2026-11-14 11:00:00', '2026-11-14 11:35:00', 1, 'SEALED', '2026-11-14 11:00:00',"
            + " '2026-11-14 11:00:00')",
        ROUND);
    DataSourceTransactionManager tm = new DataSourceTransactionManager(dataSource);
    tx = new TransactionTemplate(tm);
    AccountStore accounts = new AccountStore(jdbc, tm, clock);
    fourthSubject = accounts.login(OAuthProvider.KAKAO, "fourth").subjectId();
    long thirdSubject = accounts.login(OAuthProvider.KAKAO, "third").subjectId();
    fourthMember = memberOf(fourthSubject);
    thirdMember = memberOf(thirdSubject);
    record(fourthSubject, LottoNumbers.of(1, 2, 3, 4, 8, 9).key(), true, false);
    record(thirdSubject, LottoNumbers.of(1, 2, 3, 4, 5, 8).key(), false, true);
    consent(fourthMember);
    consent(thirdMember);
    new ResultPublisher(
            jdbc,
            tm,
            new JobStore(jdbc, tm, clock, new JobProperties(null, null)),
            new PrizeProperties(null, null),
            clock)
        .receive(
            new OfficialDraw(
                ROUND,
                new WinningNumbers(LottoNumbers.of(1, 2, 3, 4, 5, 6), LottoNumber.of(7)),
                null,
                null,
                new byte[32]));
    long resultId = jdbc.queryForObject("SELECT published_result_id FROM lotto_round", Long.class);
    tx.execute(s -> new EvaluationStore(jdbc).evaluateBatch(resultId, 0, 100));
    budget = new MessageBudgetStore(jdbc, new BudgetProperties(300_000L, 90));
    store = new ResultMessageStore(jdbc, new NotificationTargetQuery(jdbc), budget, clock);
  }

  @Test
  @DisplayName("대상 회원마다 메시지를 하나만 만들고, 마감 때 4등이던 구매를 발송 전에 취소하면 보내지 않는다(T16)")
  void createAndSuppress() {
    assertThat(count(() -> store.createMissing(ROUND))).isEqualTo(2);
    assertThat(count(() -> store.createMissing(ROUND))).isZero();
    jdbc.update(
        "UPDATE lotto_record SET purchased = FALSE, purchased_at = NULL, version = version + 1"
            + " WHERE owner_subject_id = ?",
        fourthSubject);

    assertThat(inTx(() -> store.recheck(message(fourthMember)))).isFalse();
    assertThat(inTx(() -> store.recheck(message(thirdMember)))).isTrue();
    assertThat(state(fourthMember)).isEqualTo("SUPPRESSED");
  }

  @Test
  @DisplayName("알림 수신을 끈 회원은 보내지 않는다")
  void consentOff() {
    tx.execute(s -> store.createMissing(ROUND));
    jdbc.update(
        "UPDATE member_contact SET notify_enabled = FALSE, consent_event_id = NULL WHERE member_id = ?",
        thirdMember);

    assertThat(inTx(() -> store.recheck(message(thirdMember)))).isFalse();
  }

  @Test
  @DisplayName("접수는 비용 확정, 확정 미접수는 반환 뒤 다시 시도, 접수 여부를 모르면 예약 유지이고 상태는 뒤로 가지 않는다(T18 일부)")
  void outcomes() {
    tx.execute(s -> store.createMissing(ROUND));
    long a = message(fourthMember);
    long b = message(thirdMember);

    assertThat(inTx(() -> store.startSending(a, 143))).isTrue();
    assertThat(inTx(() -> store.startSending(b, 143))).isTrue();
    tx.executeWithoutResult(s -> store.recordOutcome(a, new SendOutcome.Accepted("M-1")));
    tx.executeWithoutResult(s -> store.recordOutcome(b, new SendOutcome.NotAccepted("E-1")));
    tx.executeWithoutResult(s -> store.recordOutcome(a, new SendOutcome.Unknown()));

    assertThat(state(fourthMember)).isEqualTo("ACCEPTED");
    assertThat(state(thirdMember)).isEqualTo("RETRY_WAIT");
    assertThat(budget.matchesMessages(LocalDate.parse("2026-11-01"))).isTrue();
    assertThat(budgetRow()).containsEntry("reserved_units", 0L).containsEntry("spent_units", 143L);

    assertThat(inTx(() -> store.startSending(b, 143))).isTrue();
    tx.executeWithoutResult(s -> store.recordOutcome(b, new SendOutcome.Unknown()));
    assertThat(state(thirdMember)).isEqualTo("UNKNOWN");
    assertThat(budgetRow()).containsEntry("reserved_units", 143L);
    assertThat(inTx(() -> store.markDelivered(a))).isTrue();
    assertThat(inTx(() -> store.markDelivered(a))).isFalse();
    assertThat(state(fourthMember)).isEqualTo("DELIVERED");
  }

  @Test
  @DisplayName("예산이 모자라면 IN_FLIGHT로 가지 않고 기다리며, 마지막 슬롯 실패 때 남은 메시지는 HALTED")
  void budgetShortAndHalt() {
    store =
        new ResultMessageStore(
            jdbc,
            new NotificationTargetQuery(jdbc),
            new MessageBudgetStore(jdbc, new BudgetProperties(100L, 90)),
            clock);
    tx.execute(s -> store.createMissing(ROUND));
    long a = message(fourthMember);

    assertThat(inTx(() -> store.startSending(a, 143))).isFalse();
    assertThat(state(fourthMember)).isEqualTo("PENDING");
    assertThat(count(() -> store.haltRemaining(ROUND))).isEqualTo(2);
    assertThat(store.sendable(ROUND)).isEmpty();
  }

  private int count(Supplier<Integer> call) {
    Integer n = tx.execute(s -> call.get());
    return n == null ? 0 : n;
  }

  private boolean inTx(Supplier<Boolean> call) {
    return Boolean.TRUE.equals(tx.execute(s -> call.get()));
  }

  private long message(long memberId) {
    return jdbc.queryForObject(
        "SELECT id FROM delivery_message WHERE member_id = ?", Long.class, memberId);
  }

  private String state(long memberId) {
    return jdbc.queryForObject(
        "SELECT state FROM delivery_message WHERE member_id = ?", String.class, memberId);
  }

  private Map<String, Object> budgetRow() {
    return jdbc.queryForMap("SELECT reserved_units, spent_units FROM message_budget");
  }

  private long memberOf(long subject) {
    return jdbc.queryForObject(
        "SELECT member_id FROM actor_subject WHERE id = ?", Long.class, subject);
  }

  private void consent(long memberId) {
    jdbc.update(
        "INSERT INTO consent_event (member_id, granted, policy_version, occurred_at) VALUES (?, TRUE, 'v1', NOW(6))",
        memberId);
    long consent = jdbc.queryForObject("SELECT MAX(id) FROM consent_event", Long.class);
    jdbc.update(
        "INSERT INTO member_contact (member_id, address_ciphertext, key_version, verified_at, notify_enabled,"
            + " consent_event_id) VALUES (?, ?, 1, NOW(6), TRUE, ?)",
        memberId,
        new byte[] {1},
        consent);
  }

  private void record(long subject, String key, boolean purchased, boolean recommended) {
    jdbc.update(
        "INSERT INTO lotto_record (public_id, owner_subject_id, round_no, numbers_key, created_at, purchased,"
            + " purchased_at, recommended_at) VALUES (UUID_TO_BIN(UUID()), ?, ?, ?, '2026-11-10 00:00:00', ?, ?, ?)",
        subject,
        ROUND,
        key,
        purchased,
        purchased ? "2026-11-10 00:00:00" : null,
        recommended ? "2026-11-10 00:00:00" : null);
  }
}
