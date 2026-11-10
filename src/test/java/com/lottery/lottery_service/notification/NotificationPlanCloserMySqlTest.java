package com.lottery.lottery_service.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.lottery.lottery_service.account.AccountStore;
import com.lottery.lottery_service.auth.OAuthProvider;
import com.lottery.lottery_service.common.job.JobProperties;
import com.lottery.lottery_service.common.job.JobStore;
import com.lottery.lottery_service.common.number.LottoNumber;
import com.lottery.lottery_service.common.number.LottoNumbers;
import com.lottery.lottery_service.result.domain.WinningNumbers;
import com.lottery.lottery_service.result.evaluate.EvaluationStore;
import com.lottery.lottery_service.result.publish.PrizeProperties;
import com.lottery.lottery_service.result.publish.ResultPublisher;
import com.lottery.lottery_service.result.source.OfficialDraw;
import com.lottery.lottery_service.support.MutableClock;
import com.lottery.lottery_service.support.MySqlContainerSupport;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 알림 계획 완료 조건을 MySQL에서 확인한다. */
class NotificationPlanCloserMySqlTest extends MySqlContainerSupport {

  private static final int ROUND = 1200;

  private static DataSource dataSource;

  private final MutableClock clock = new MutableClock(Instant.parse("2026-11-14T12:00:00Z"));
  private JdbcTemplate jdbc;
  private DataSourceTransactionManager tm;
  private long memberId;
  private Instant publishedAt;

  @BeforeAll
  static void migrate() throws Exception {
    dataSource = migratedDatabase("notification_plan");
  }

  @BeforeEach
  void setUp() {
    jdbc = new JdbcTemplate(dataSource);
    for (String t :
        List.of(
            "delivery_message",
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
    tm = new DataSourceTransactionManager(dataSource);
    long subject = new AccountStore(jdbc, tm, clock).login(OAuthProvider.KAKAO, "m").subjectId();
    memberId =
        jdbc.queryForObject(
            "SELECT member_id FROM actor_subject WHERE id = ?", Long.class, subject);
    jdbc.update(
        "INSERT INTO lotto_record (public_id, owner_subject_id, round_no, numbers_key, created_at,"
            + " recommended_at) VALUES (UUID_TO_BIN(UUID()), ?, ?, '01 02 03 04 05 08',"
            + " '2026-11-10 00:00:00', '2026-11-10 00:00:00')",
        subject,
        ROUND);
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
    publishedAt =
        jdbc.queryForObject("SELECT published_at FROM draw_result", LocalDateTime.class)
            .toInstant(ZoneOffset.UTC);
  }

  @Test
  @DisplayName("기한 전, 판정 전, UNKNOWN이 남았으면 세우지 않고, 모두 끝나면 한 번만 세운다(T44)")
  void closesOnlyWhenEverythingIsFinal() {
    jdbc.update(
        "INSERT INTO delivery_message (public_id, purpose, member_id, round_no, state,"
            + " provider_request_key, expires_at) VALUES (UUID_TO_BIN(UUID()), 'RESULT', ?, ?, 'UNKNOWN',"
            + " UUID_TO_BIN(UUID()), NOW(6))",
        memberId,
        ROUND);
    NotificationPlanCloser closer = new NotificationPlanCloser(jdbc, clock);

    Instant lastSlot = Instant.parse("2026-11-15T03:00:00Z");
    Instant deadline = publishedAt.plus(Duration.ofDays(7));
    Instant ready = deadline.isAfter(lastSlot) ? deadline : lastSlot;
    clock.set(ready.minusSeconds(1));
    assertThat(close(closer)).isFalse();
    clock.set(ready);
    assertThat(close(closer)).isFalse();
    long resultId = jdbc.queryForObject("SELECT published_result_id FROM lotto_round", Long.class);
    new TransactionTemplate(tm)
        .execute(s -> new EvaluationStore(jdbc).evaluateBatch(resultId, 0, 100));
    assertThat(close(closer)).isFalse();

    jdbc.update("UPDATE delivery_message SET state = 'FAILED'");
    assertThat(close(closer)).isTrue();
    assertThat(close(closer)).isFalse();
    assertThat(
            jdbc.queryForObject(
                "SELECT notification_plan_closed_at IS NOT NULL FROM lotto_round", Boolean.class))
        .isTrue();
  }

  @Test
  @DisplayName("기한이 지나도록 못 보낸 메시지는 EXPIRED가 되고 계획을 닫는다. 기한 전에는 그대로 둔다")
  void expiresUnsentMessagesAfterDeadline() {
    AccountStore accounts = new AccountStore(jdbc, tm, clock);
    for (String state : List.of("HALTED", "RETRY_WAIT", "PENDING")) {
      long subject = accounts.login(OAuthProvider.KAKAO, "x-" + state).subjectId();
      long member =
          jdbc.queryForObject(
              "SELECT member_id FROM actor_subject WHERE id = ?", Long.class, subject);
      jdbc.update(
          "INSERT INTO delivery_message (public_id, purpose, member_id, round_no, state,"
              + " provider_request_key, expires_at) VALUES (UUID_TO_BIN(UUID()), 'RESULT', ?, ?, ?,"
              + " UUID_TO_BIN(UUID()), NOW(6))",
          member,
          ROUND,
          state);
    }
    long resultId = jdbc.queryForObject("SELECT published_result_id FROM lotto_round", Long.class);
    new TransactionTemplate(tm)
        .execute(s -> new EvaluationStore(jdbc).evaluateBatch(resultId, 0, 100));
    NotificationPlanCloser closer = new NotificationPlanCloser(jdbc, clock);
    Instant lastSlot = Instant.parse("2026-11-15T03:00:00Z");
    Instant deadline = publishedAt.plus(Duration.ofDays(7));
    Instant ready = deadline.isAfter(lastSlot) ? deadline : lastSlot;

    clock.set(ready.minusSeconds(1));
    assertThat(close(closer)).isFalse();
    assertThat(expired()).isZero();

    clock.set(ready);
    assertThat(close(closer)).isTrue();
    assertThat(expired()).isEqualTo(3);
  }

  private long expired() {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM delivery_message WHERE state = 'EXPIRED'", Long.class);
  }

  private boolean close(NotificationPlanCloser closer) {
    Boolean result = new TransactionTemplate(tm).execute(s -> closer.closeIfDone(ROUND));
    return Boolean.TRUE.equals(result);
  }
}
