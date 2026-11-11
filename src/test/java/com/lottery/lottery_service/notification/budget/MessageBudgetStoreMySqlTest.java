package com.lottery.lottery_service.notification.budget;

import static org.assertj.core.api.Assertions.assertThat;

import com.lottery.lottery_service.account.AccountStore;
import com.lottery.lottery_service.auth.OAuthProvider;
import com.lottery.lottery_service.support.MySqlContainerSupport;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 메시지 비용과 월 예산 카운터를 MySQL에서 확인한다. */
class MessageBudgetStoreMySqlTest extends MySqlContainerSupport {

  private static final Instant NOV = Instant.parse("2026-11-14T13:00:00Z");
  private static final LocalDate NOV_MONTH = LocalDate.parse("2026-11-01");

  private static DataSource dataSource;

  private JdbcTemplate jdbc;
  private TransactionTemplate tx;
  private MessageBudgetStore store;
  private long memberId;
  private int nextRound = 1200;

  @BeforeAll
  static void migrate() throws Exception {
    dataSource = migratedDatabase("message_budget");
  }

  @BeforeEach
  void setUp() {
    jdbc = new JdbcTemplate(dataSource);
    for (String t :
        List.of(
            "delivery_message",
            "verification_challenge",
            "message_budget",
            "lotto_round",
            "oauth_account",
            "actor_subject",
            "member")) {
      jdbc.update("DELETE FROM " + t);
    }
    DataSourceTransactionManager tm = new DataSourceTransactionManager(dataSource);
    tx = new TransactionTemplate(tm);
    store = new MessageBudgetStore(jdbc, new BudgetProperties(300_000L, 90));
    long subject =
        new AccountStore(jdbc, tm, Clock.fixed(NOV, ZoneOffset.UTC))
            .login(OAuthProvider.KAKAO, "m")
            .subjectId();
    memberId =
        jdbc.queryForObject(
            "SELECT member_id FROM actor_subject WHERE id = ?", Long.class, subject);
  }

  @Test
  @DisplayName("그 달 행이 없으면 설정값으로 만든 뒤 예약하고, 예산 부족으로 잘못 판단하지 않는다(T55)")
  void createsMonthRow() {
    long message = message();

    assertThat(inTx(() -> store.reserve(message, MessagePurpose.RESULT, 143, NOV))).isTrue();

    assertThat(budget(NOV_MONTH))
        .containsEntry("limit_units", 300_000L)
        .containsEntry("reserved_units", 143L)
        .containsEntry("spent_units", 0L);
  }

  @Test
  @DisplayName("기본 알림은 월 예산의 90%까지, 인증 코드는 남은 10%까지 쓸 수 있다")
  void caps() {
    assertThat(reserve(message(), MessagePurpose.RESULT, 135_000)).isTrue();
    assertThat(reserve(message(), MessagePurpose.RESULT, 135_000)).isTrue();
    assertThat(reserve(message(), MessagePurpose.RESULT, 1)).isFalse();
    assertThat(reserve(verification(), MessagePurpose.VERIFICATION, 30_000)).isTrue();
    assertThat(reserve(verification(), MessagePurpose.VERIFICATION, 1)).isFalse();
  }

  @Test
  @DisplayName("기본 알림이 10% 넘게 써도 인증 코드는 자기 몫 10%를 쓸 수 있고, 그 몫을 넘지 못한다(2026-11-09 Q14)")
  void verificationShareIsOwnSum() {
    assertThat(reserve(message(), MessagePurpose.RESULT, 50_000)).isTrue();

    assertThat(reserve(verification(), MessagePurpose.VERIFICATION, 29_000)).isTrue();
    long last = verification();
    assertThat(reserve(last, MessagePurpose.VERIFICATION, 1_001)).isFalse();
    assertThat(reserve(last, MessagePurpose.VERIFICATION, 1_000)).isTrue();
    assertThat(reserve(verification(), MessagePurpose.VERIFICATION, 1)).isFalse();

    inTx(() -> store.release(last));
    assertThat(reserve(verification(), MessagePurpose.VERIFICATION, 1_000)).isTrue();
    assertThat(budget(NOV_MONTH)).containsEntry("reserved_units", 80_000L);
    assertThat(store.matchesMessages(NOV_MONTH)).isTrue();
  }

  @Test
  @DisplayName("인증 코드를 쓴 만큼 기본 알림의 90% 합계 상한에 들어간다")
  void resultCapCountsVerification() {
    assertThat(reserve(verification(), MessagePurpose.VERIFICATION, 30_000)).isTrue();

    assertThat(reserve(message(), MessagePurpose.RESULT, 240_000)).isTrue();
    assertThat(reserve(message(), MessagePurpose.RESULT, 1)).isFalse();
  }

  @Test
  @DisplayName("접수 확정과 반환은 한 번만 반영되고, 반환한 메시지는 다시 예약할 수 있으며 카운터와 메시지 합계가 같다(T19)")
  void spendReleaseOnce() {
    long accepted = message();
    long retried = message();
    long unknown = message();
    reserve(accepted, MessagePurpose.RESULT, 143);
    reserve(retried, MessagePurpose.RESULT, 143);
    reserve(unknown, MessagePurpose.RESULT, 143);

    assertThat(inTx(() -> store.spend(accepted))).isTrue();
    assertThat(inTx(() -> store.spend(accepted))).isFalse();
    assertThat(inTx(() -> store.release(retried))).isTrue();
    assertThat(inTx(() -> store.release(retried))).isFalse();

    assertThat(budget(NOV_MONTH))
        .containsEntry("reserved_units", 143L)
        .containsEntry("spent_units", 143L);
    assertThat(store.matchesMessages(NOV_MONTH)).isTrue();
    assertThat(reserve(retried, MessagePurpose.RESULT, 143)).isTrue();
    assertThat(store.matchesMessages(NOV_MONTH)).isTrue();

    jdbc.update("UPDATE message_budget SET spent_units = spent_units + 1");
    assertThat(store.matchesMessages(NOV_MONTH)).isFalse();
  }

  @Test
  @DisplayName("서울 기준 달로 나눈다. UTC 11월 30일 15:00는 서울 12월 1일이다")
  void seoulMonth() {
    long message = message();
    tx.execute(
        s ->
            store.reserve(
                message, MessagePurpose.RESULT, 143, Instant.parse("2026-11-30T15:00:00Z")));

    assertThat(budget(LocalDate.parse("2026-12-01"))).containsEntry("reserved_units", 143L);
  }

  private boolean reserve(long message, MessagePurpose purpose, long units) {
    return inTx(() -> store.reserve(message, purpose, units, NOV));
  }

  private boolean inTx(Supplier<Boolean> call) {
    Boolean result = tx.execute(s -> call.get());
    return Boolean.TRUE.equals(result);
  }

  private long message() {
    int round = nextRound++;
    jdbc.update(
        "INSERT INTO lotto_round (round_no, sales_open_at, sales_close_at, draw_at, schedule_version)"
            + " VALUES (?, '2026-11-07 21:00:00', '2026-11-14 11:00:00', '2026-11-14 11:35:00', 1)",
        round);
    String key = UUID.randomUUID().toString();
    jdbc.update(
        "INSERT INTO delivery_message (public_id, purpose, member_id, round_no, state,"
            + " provider_request_key, expires_at) VALUES (UUID_TO_BIN(UUID()), 'RESULT', ?, ?,"
            + " 'PENDING', UUID_TO_BIN(?), '2026-11-21 11:35:00')",
        memberId,
        round,
        key);
    return jdbc.queryForObject(
        "SELECT id FROM delivery_message WHERE provider_request_key = UUID_TO_BIN(?)",
        Long.class,
        key);
  }

  private long verification() {
    String challenge = UUID.randomUUID().toString();
    jdbc.update(
        "INSERT INTO verification_challenge (public_id, member_id, target_hmac, key_version,"
            + " expected_contact_version, state, expires_at) VALUES (UUID_TO_BIN(?), ?,"
            + " UNHEX(SHA2('t', 256)), 1, 0, 'ACTIVE', '2026-11-14 13:05:00')",
        challenge,
        memberId);
    String key = UUID.randomUUID().toString();
    jdbc.update(
        "INSERT INTO delivery_message (public_id, purpose, member_id, challenge_id, state,"
            + " provider_request_key, expires_at) SELECT UUID_TO_BIN(UUID()), 'VERIFICATION', ?,"
            + " id, 'PENDING', UUID_TO_BIN(?), '2026-11-14 13:05:00' FROM verification_challenge"
            + " WHERE public_id = UUID_TO_BIN(?)",
        memberId,
        key,
        challenge);
    return jdbc.queryForObject(
        "SELECT id FROM delivery_message WHERE provider_request_key = UUID_TO_BIN(?)",
        Long.class,
        key);
  }

  private Map<String, Object> budget(LocalDate month) {
    return jdbc.queryForMap(
        "SELECT limit_units, reserved_units, spent_units FROM message_budget WHERE business_month = ?",
        month);
  }
}
