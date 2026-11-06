package com.lottery.lottery_service.account.withdrawal;

import static org.assertj.core.api.Assertions.assertThat;

import com.lottery.lottery_service.account.AccountStore;
import com.lottery.lottery_service.account.GuestCredentialStore;
import com.lottery.lottery_service.account.MemberLogin;
import com.lottery.lottery_service.account.SubjectClaimStore;
import com.lottery.lottery_service.auth.OAuthProvider;
import com.lottery.lottery_service.common.job.JobProperties;
import com.lottery.lottery_service.common.job.JobRunner;
import com.lottery.lottery_service.common.job.JobState;
import com.lottery.lottery_service.common.job.JobStore;
import com.lottery.lottery_service.notification.ProviderStatus;
import com.lottery.lottery_service.notification.UnknownMessageReconciler;
import com.lottery.lottery_service.notification.budget.BudgetProperties;
import com.lottery.lottery_service.notification.budget.MessageBudgetStore;
import com.lottery.lottery_service.notification.budget.MessagePurpose;
import com.lottery.lottery_service.stats.UsageEventStore;
import com.lottery.lottery_service.support.MutableClock;
import com.lottery.lottery_service.support.MySqlContainerSupport;
import com.lottery.lottery_service.support.TestSecrets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 탈퇴 차단과 삭제 job을 MySQL에서 확인한다. */
class WithdrawalMySqlTest extends MySqlContainerSupport {

  private static final int ROUND = 1200;
  private static final List<String> PERSONAL =
      List.of(
          "lotto_record",
          "recommendation_seen",
          "recommendation_item",
          "recommendation_request",
          "history_import_item",
          "subject_claim",
          "guest_credential",
          "member_contact",
          "consent_event",
          "command_operation",
          "oauth_account");

  private static DataSource dataSource;

  private final MutableClock clock = new MutableClock(Instant.parse("2026-11-14T12:00:00Z"));
  private final List<UUID> journaled = new ArrayList<>();
  private JdbcTemplate jdbc;
  private DataSourceTransactionManager tm;
  private JobStore jobs;
  private MessageBudgetStore budget;
  private MemberLogin member;
  private long guest;
  private long message;

  @BeforeAll
  static void migrate() throws Exception {
    dataSource = migratedDatabase("withdrawal");
  }

  @BeforeEach
  void setUp() {
    jdbc = new JdbcTemplate(dataSource);
    for (String t :
        List.of(
            "job",
            "usage_event",
            "message_budget",
            "delivery_message",
            "member_contact",
            "consent_event",
            "command_operation",
            "recommendation_seen",
            "recommendation_item",
            "recommendation_request",
            "history_import_item",
            "lotto_record",
            "subject_claim",
            "guest_credential",
            "oauth_account",
            "actor_subject",
            "member",
            "lotto_round")) {
      jdbc.update("DELETE FROM " + t);
    }
    tm = new DataSourceTransactionManager(dataSource);
    jobs = new JobStore(jdbc, tm, clock, new JobProperties(5, Duration.ofMinutes(5)));
    budget = new MessageBudgetStore(jdbc, new BudgetProperties(null, null));
    jdbc.update(
        "INSERT INTO lotto_round (round_no, sales_open_at, sales_close_at, draw_at, schedule_version)"
            + " VALUES (?, '2026-11-07 21:00:00', '2026-11-14 11:00:00', '2026-11-14 11:35:00', 1)",
        ROUND);
    member = new AccountStore(jdbc, tm, clock).login(OAuthProvider.KAKAO, "leaver");
    guest = new GuestCredentialStore(jdbc, tm, clock, TestSecrets.keys()).issue().subjectId();
    new SubjectClaimStore(jdbc, tm, clock).claim(guest, member.subjectId());
    for (long subject : List.of(member.subjectId(), guest)) {
      jdbc.update(
          "INSERT INTO lotto_record (public_id, owner_subject_id, round_no, numbers_key, recommended_at)"
              + " VALUES (UUID_TO_BIN(UUID()), ?, ?, '01 02 03 04 05 06', NOW(6))",
          subject,
          ROUND);
      jdbc.update(
          "INSERT INTO recommendation_request (public_id, subject_id, round_no, key_digest, request_hash,"
              + " accepted_at, policy_version, generation_mode, filter_version, response_expires_at)"
              + " VALUES (UUID_TO_BIN(UUID()), ?, ?, ?, ?, NOW(6), 1, 'BASIC', 'FILTER_V1', NOW(6))",
          subject,
          ROUND,
          new byte[32],
          new byte[32]);
      long request = jdbc.queryForObject("SELECT MAX(id) FROM recommendation_request", Long.class);
      long record = jdbc.queryForObject("SELECT MAX(id) FROM lotto_record", Long.class);
      jdbc.update(
          "INSERT INTO recommendation_item (request_id, ordinal, record_id, numbers_key)"
              + " VALUES (?, 1, ?, '01 02 03 04 05 06')",
          request,
          record);
      jdbc.update(
          "INSERT INTO recommendation_seen (subject_id, round_no, numbers_key, first_request_id)"
              + " VALUES (?, ?, '01 02 03 04 05 06', ?)",
          subject,
          ROUND,
          request);
      new UsageEventStore(jdbc)
          .record(subject, ROUND, UsageEventStore.RECOMMENDATION_SETS, 5, clock.instant());
    }
    jdbc.update(
        "INSERT INTO consent_event (member_id, granted, policy_version, occurred_at)"
            + " VALUES (?, TRUE, 'v1', NOW(6))",
        member.memberId());
    long consent = jdbc.queryForObject("SELECT MAX(id) FROM consent_event", Long.class);
    jdbc.update(
        "INSERT INTO member_contact (member_id, address_ciphertext, key_version, verified_at,"
            + " notify_enabled, consent_event_id) VALUES (?, ?, 1, NOW(6), TRUE, ?)",
        member.memberId(),
        new byte[] {1, 2, 3},
        consent);
    jdbc.update(
        "INSERT INTO command_operation (public_id, subject_id, operation, token_id, client_key,"
            + " request_hash, state, token_expires_at, marker_expires_at) VALUES (UUID_TO_BIN(UUID()),"
            + " ?, 'RECOMMEND', ?, ?, ?, 'SUCCEEDED', NOW(6), NOW(6))",
        member.subjectId(),
        new byte[16],
        new byte[16],
        new byte[32]);
    jdbc.update(
        "INSERT INTO delivery_message (public_id, purpose, member_id, round_no, state,"
            + " provider_request_key, consent_event_id, payload_ciphertext, recipient_ciphertext,"
            + " expires_at) VALUES (UUID_TO_BIN(UUID()), 'RESULT', ?, ?, 'UNKNOWN', UUID_TO_BIN(UUID()),"
            + " ?, ?, ?, NOW(6))",
        member.memberId(),
        ROUND,
        consent,
        new byte[] {9},
        new byte[] {8});
    message = jdbc.queryForObject("SELECT id FROM delivery_message", Long.class);
    new TransactionTemplate(tm)
        .execute(s -> budget.reserve(message, MessagePurpose.RESULT, 143, clock.instant()));
  }

  @Test
  @DisplayName("탈퇴하면 즉시 차단되고, 삭제 job이 개인 자료를 순서대로 지우며 통계와 메시지 비용 기록은 남긴다(T39, T56)")
  void withdrawalDeletesPersonalData() {
    new WithdrawalService(jdbc, tm, jobs, clock).block(member.memberId());

    assertThat(state("member", member.memberId())).isEqualTo("WITHDRAWING");
    assertThat(jdbc.queryForObject("SELECT auth_version FROM member", Long.class)).isEqualTo(1);
    assertThat(runner(journal()).runOnce()).contains(JobState.SUCCEEDED);

    assertThat(journaled).hasSize(1);
    for (String table : PERSONAL) {
      assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class))
          .as(table)
          .isZero();
    }
    assertThat(state("member", member.memberId())).isEqualTo("WITHDRAWN");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM actor_subject", Long.class)).isEqualTo(1);
    assertThat(
            jdbc.queryForMap(
                "SELECT state, cost_state, payload_ciphertext, recipient_ciphertext FROM delivery_message"))
        .containsEntry("state", "FAILED")
        .containsEntry("cost_state", "SPENT")
        .containsEntry("payload_ciphertext", null)
        .containsEntry("recipient_ciphertext", null);
    assertThat(budget.matchesMessages(LocalDate.parse("2026-11-01"))).isTrue();
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM usage_event WHERE subject_id IS NULL"
                    + " AND metric = 'RECOMMENDATION_SETS'",
                Long.class))
        .isEqualTo(2);

    MemberLogin again = new AccountStore(jdbc, tm, clock).login(OAuthProvider.KAKAO, "leaver");
    assertThat(again.newMember()).isTrue();
    assertThat(again.memberId()).isNotEqualTo(member.memberId());
  }

  @Test
  @DisplayName("삭제 기록을 남기지 못하면 차단은 유지하고 아무것도 지우지 않은 채 다시 시도하며, 시도 상한에 닿으면 DEAD와 운영자 알림(T30 일부)")
  void journalFailureKeepsBlock() {
    new WithdrawalService(jdbc, tm, jobs, clock).block(member.memberId());

    assertThat(runner(new UnavailableDeletionJournal()).runOnce()).contains(JobState.RETRY_WAIT);

    assertThat(state("member", member.memberId())).isEqualTo("WITHDRAWING");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lotto_record", Long.class)).isEqualTo(2);
    assertThat(
            jdbc.queryForObject(
                "SELECT JSON_UNQUOTE(JSON_EXTRACT(payload, '$.kind')) FROM job"
                    + " WHERE job_type = 'OPERATOR_ALERT'",
                String.class))
        .isEqualTo("DELETION_JOURNAL_FAILED");
    for (int i = 0; i < 4; i++) {
      clock.set(clock.instant().plus(Duration.ofMinutes(2)));
      runner(new UnavailableDeletionJournal()).runOnce();
    }
    assertThat(
            jdbc.queryForObject(
                "SELECT state FROM job WHERE job_type <> 'OPERATOR_ALERT'", String.class))
        .isEqualTo("DEAD");
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM job WHERE job_type = 'OPERATOR_ALERT'", Long.class))
        .isEqualTo(2);
    assertThat(state("member", member.memberId())).isEqualTo("WITHDRAWING");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lotto_record", Long.class)).isEqualTo(2);
  }

  @Test
  @DisplayName("결과 알림을 받은 회원이 같은 달에 탈퇴해도 메시지 비용 기록이 남아 예산 카운터와 합계가 맞는다(T54)")
  void acceptedMessageKeepsBudgetAfterWithdrawal() {
    jdbc.update("UPDATE delivery_message SET state = 'ACCEPTED', provider_message_id = 'M-1'");
    new TransactionTemplate(tm).execute(s -> budget.spend(message));
    assertThat(budget.matchesMessages(LocalDate.parse("2026-11-01"))).isTrue();

    new WithdrawalService(jdbc, tm, jobs, clock).block(member.memberId());
    assertThat(runner(journal()).runOnce()).contains(JobState.SUCCEEDED);

    assertThat(
            jdbc.queryForMap(
                "SELECT state, cost_state, cost_units, payload_ciphertext FROM delivery_message"))
        .containsEntry("state", "ACCEPTED")
        .containsEntry("cost_state", "SPENT")
        .containsEntry("cost_units", 143L)
        .containsEntry("payload_ciphertext", null);
    assertThat(budget.matchesMessages(LocalDate.parse("2026-11-01"))).isTrue();
    assertThat(jdbc.queryForMap("SELECT reserved_units, spent_units FROM message_budget"))
        .containsEntry("reserved_units", 0L)
        .containsEntry("spent_units", 143L);
  }

  @Test
  @DisplayName("탈퇴 뒤 늦게 온 제공자 조회 결과는 끝난 메시지를 되돌리지 않고 개인 자료를 되살리지 않는다(T22 일부)")
  void lateProviderResultAfterWithdrawal() {
    new WithdrawalService(jdbc, tm, jobs, clock).block(member.memberId());
    assertThat(runner(journal()).runOnce()).contains(JobState.SUCCEEDED);

    UnknownMessageReconciler reconciler = new UnknownMessageReconciler(jdbc, budget, jobs, clock);
    String after =
        new TransactionTemplate(tm)
            .execute(s -> reconciler.apply(message, new ProviderStatus.Accepted("M-late")));

    assertThat(after).isEqualTo("FAILED");
    assertThat(
            jdbc.queryForMap(
                "SELECT state, provider_message_id, payload_ciphertext, recipient_ciphertext,"
                    + " communicated_summary FROM delivery_message"))
        .containsEntry("state", "FAILED")
        .containsEntry("provider_message_id", null)
        .containsEntry("payload_ciphertext", null)
        .containsEntry("recipient_ciphertext", null)
        .containsEntry("communicated_summary", null);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lotto_record", Long.class)).isZero();
    assertThat(budget.matchesMessages(LocalDate.parse("2026-11-01"))).isTrue();
  }

  private DeletionJournal journal() {
    return (memberPublicId, at) -> journaled.add(memberPublicId);
  }

  private JobRunner runner(DeletionJournal journal) {
    return new JobRunner(
        jobs, List.of(new WithdrawalJobHandler(jdbc, journal, budget, jobs, tm, clock)), tm, clock);
  }

  private String state(String table, long id) {
    return jdbc.queryForObject("SELECT state FROM " + table + " WHERE id = ?", String.class, id);
  }
}
