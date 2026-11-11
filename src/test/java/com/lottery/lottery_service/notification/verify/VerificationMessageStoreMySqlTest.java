package com.lottery.lottery_service.notification.verify;

import static org.assertj.core.api.Assertions.assertThat;

import com.lottery.lottery_service.account.AccountStore;
import com.lottery.lottery_service.auth.OAuthProvider;
import com.lottery.lottery_service.notification.SendOutcome;
import com.lottery.lottery_service.notification.budget.BudgetProperties;
import com.lottery.lottery_service.notification.budget.MessageBudgetStore;
import com.lottery.lottery_service.support.MutableClock;
import com.lottery.lottery_service.support.MySqlContainerSupport;
import com.lottery.lottery_service.support.TestSecrets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 인증 코드 메시지의 멈춤 처리와 기한 지난 인증 요청 정리를 MySQL에서 확인한다(05 8.3·8.8절). */
class VerificationMessageStoreMySqlTest extends MySqlContainerSupport {

  private static DataSource dataSource;

  private final MutableClock clock = new MutableClock(Instant.parse("2026-11-08T01:00:00Z"));
  private JdbcTemplate jdbc;
  private TransactionTemplate tx;
  private VerificationStore verifications;
  private VerificationMessageStore messages;
  private long member;

  @BeforeAll
  static void migrate() throws Exception {
    dataSource = migratedDatabase("verification_message");
  }

  @BeforeEach
  void setUp() {
    jdbc = new JdbcTemplate(dataSource);
    for (String t :
        List.of(
            "delivery_message",
            "message_budget",
            "verification_rate_bucket",
            "verification_challenge",
            "member_contact",
            "lotto_round",
            "oauth_account",
            "actor_subject",
            "member")) {
      jdbc.update("DELETE FROM " + t);
    }
    DataSourceTransactionManager tm = new DataSourceTransactionManager(dataSource);
    tx = new TransactionTemplate(tm);
    verifications =
        new VerificationStore(
            jdbc, TestSecrets.keys(), new ContactCipher(TestSecrets.keys()), clock);
    messages =
        new VerificationMessageStore(
            jdbc, new MessageBudgetStore(jdbc, new BudgetProperties(300_000L, 90)), clock);
    long subject = new AccountStore(jdbc, tm, clock).login(OAuthProvider.KAKAO, "m").subjectId();
    member =
        jdbc.queryForObject(
            "SELECT member_id FROM actor_subject WHERE id = ?", Long.class, subject);
  }

  @Test
  @DisplayName("IN_FLIGHT로 5분 넘게 남은 인증 메시지만 UNKNOWN으로 넘기고, 5분 안이거나 결과 알림 메시지는 그대로 둔다")
  void staleInFlightBecomesUnknown() {
    long message = start("+821011112222");
    insertResultInFlight();

    clock.set(clock.instant().plus(VerificationMessageStore.STALE_IN_FLIGHT));
    assertThat(staleSweep()).isZero();

    clock.set(clock.instant().plusSeconds(1));
    assertThat(staleSweep()).isEqualTo(1);
    assertThat(state(message))
        .containsEntry("state", "UNKNOWN")
        .containsEntry("cost_state", "RESERVED");
    assertThat(
            jdbc.queryForObject(
                "SELECT state FROM delivery_message WHERE purpose = 'RESULT'", String.class))
        .isEqualTo("IN_FLIGHT");

    assertThat(
            (Object)
                tx.execute(s -> messages.recordOutcome(message, new SendOutcome.Accepted("M"))))
        .isEqualTo("UNKNOWN");
  }

  @Test
  @DisplayName("확인 요청 없이 기한이 지난 인증 요청은 EXPIRED가 되고 코드 HMAC이 지워진다. 기한 전이거나 이미 끝난 요청은 그대로다")
  void expiresUnusedChallenges() {
    IssuedChallenge first = tx.execute(s -> verifications.issue(member, "+821033334444", 0));

    assertThat(expireSweep()).isZero();
    clock.set(first.expiresAt());
    assertThat(expireSweep()).isEqualTo(1);
    assertThat(
            jdbc.queryForMap(
                "SELECT state, code_hmac FROM verification_challenge WHERE id = ?",
                first.challengeId()))
        .containsEntry("state", "EXPIRED")
        .containsEntry("code_hmac", null);
    assertThat(expireSweep()).isZero();
  }

  private int staleSweep() {
    Integer n = tx.execute(s -> messages.markStaleInFlightUnknown());
    return n == null ? -1 : n;
  }

  private int expireSweep() {
    Integer n = tx.execute(s -> verifications.expireDue(100));
    return n == null ? -1 : n;
  }

  private long start(String phone) {
    return tx.execute(
        s -> messages.startSending(member, verifications.issue(member, phone, 0)).orElseThrow());
  }

  private void insertResultInFlight() {
    jdbc.update(
        "INSERT INTO lotto_round (round_no, sales_open_at, sales_close_at, draw_at, schedule_version)"
            + " VALUES (1200, '2026-11-01 00:00:00', '2026-11-07 11:00:00', '2026-11-07 11:35:00', 1)");
    jdbc.update(
        "INSERT INTO delivery_message (public_id, purpose, member_id, round_no, state,"
            + " provider_request_key, last_attempt_at, expires_at) VALUES (UUID_TO_BIN(UUID()),"
            + " 'RESULT', ?, 1200, 'IN_FLIGHT', UUID_TO_BIN(UUID()), '2026-11-07 12:00:00',"
            + " '2026-11-14 11:35:00')",
        member);
  }

  private Map<String, Object> state(long message) {
    return jdbc.queryForMap("SELECT state, cost_state FROM delivery_message WHERE id = ?", message);
  }
}
