package com.lottery.lottery_service.record;

import static org.assertj.core.api.Assertions.assertThat;

import com.lottery.lottery_service.account.AccountStore;
import com.lottery.lottery_service.account.GuestCredential;
import com.lottery.lottery_service.account.GuestCredentialStore;
import com.lottery.lottery_service.auth.OAuthProvider;
import com.lottery.lottery_service.common.job.JobProperties;
import com.lottery.lottery_service.common.job.JobStore;
import com.lottery.lottery_service.stats.UsageEventStore;
import com.lottery.lottery_service.support.MutableClock;
import com.lottery.lottery_service.support.MySqlContainerSupport;
import com.lottery.lottery_service.support.TestSecrets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/** 매일 보관 정리의 나머지 대상을 MySQL에서 확인한다. */
class HousekeepingCleanerMySqlTest extends MySqlContainerSupport {

  private static final Instant T0 = Instant.parse("2026-11-01T00:00:00Z");

  private static DataSource dataSource;

  private final MutableClock clock = new MutableClock(T0);
  private JdbcTemplate jdbc;
  private DataSourceTransactionManager tm;

  @BeforeAll
  static void migrate() throws Exception {
    dataSource = migratedDatabase("housekeeping");
  }

  @BeforeEach
  void setUp() {
    jdbc = new JdbcTemplate(dataSource);
    jdbc.update("UPDATE lotto_round SET published_result_id = NULL");
    for (String t :
        List.of(
            "job",
            "usage_aggregate",
            "delivery_message",
            "verification_challenge",
            "oauth_account",
            "usage_event",
            "recommendation_seen",
            "recommendation_item",
            "recommendation_request",
            "winning_evaluation",
            "lotto_record",
            "guest_credential",
            "command_operation",
            "admin_audit_event",
            "actor_subject",
            "member",
            "draw_result",
            "lotto_round")) {
      jdbc.update("DELETE FROM " + t);
    }
    tm = new DataSourceTransactionManager(dataSource);
  }

  @Test
  @DisplayName("만료 후 7일이 지난 자격과, 자료가 없는 비회원 주체를 지우고 사용량 사건은 주체 없이 남긴다")
  void guests() {
    GuestCredentialStore guests = new GuestCredentialStore(jdbc, tm, clock, TestSecrets.keys());
    GuestCredential expired = guests.issue();
    clock.set(T0.plus(Duration.ofDays(10)));
    GuestCredential active = guests.issue();
    new UsageEventStore(jdbc).record(expired.subjectId(), null, "RECOMMENDATION_SETS", 5, T0);
    HousekeepingCleaner cleaner = new HousekeepingCleaner(jdbc, clock);

    clock.set(expired.expiresAt().plus(Duration.ofDays(7)).minusSeconds(1));
    assertThat(cleaner.deleteExpiredGuestCredentials()).isZero();
    clock.set(expired.expiresAt().plus(Duration.ofDays(7)));
    assertThat(cleaner.deleteExpiredGuestCredentials()).isEqualTo(1);
    assertThat(cleaner.deleteEmptyGuestSubjects(100)).isEqualTo(1);

    assertThat(jdbc.queryForList("SELECT id FROM actor_subject", Long.class))
        .containsExactly(active.subjectId());
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM usage_event WHERE subject_id IS NULL", Long.class))
        .isEqualTo(1);
  }

  @Test
  @DisplayName("표시 기한이 지난 멱등 기록만 지우고, 기한 전 기록은 남긴다(02 8절)")
  void commandOperations() {
    GuestCredential guest = new GuestCredentialStore(jdbc, tm, clock, TestSecrets.keys()).issue();
    commandOperation(guest.subjectId(), T0.plus(Duration.ofDays(7)));
    commandOperation(guest.subjectId(), T0.plus(Duration.ofDays(8)));
    HousekeepingCleaner cleaner = new HousekeepingCleaner(jdbc, clock);

    clock.set(T0.plus(Duration.ofDays(7)).minusSeconds(1));
    assertThat(cleaner.deleteExpiredCommandOperations(100)).isZero();
    clock.set(T0.plus(Duration.ofDays(7)));
    assertThat(cleaner.deleteExpiredCommandOperations(100)).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT marker_expires_at FROM command_operation", java.time.LocalDateTime.class))
        .isEqualTo(java.time.LocalDateTime.parse("2026-11-09T00:00:00"));
  }

  private void commandOperation(long subjectId, Instant markerExpiresAt) {
    jdbc.update(
        "INSERT INTO command_operation (public_id, subject_id, operation, token_id, client_key,"
            + " request_hash, state, token_expires_at, marker_expires_at)"
            + " VALUES (UUID_TO_BIN(UUID()), ?, 'RECOMMEND', UUID_TO_BIN(UUID()),"
            + " UUID_TO_BIN(UUID()), ?, 'SUCCEEDED', ?, ?)",
        subjectId,
        new byte[32],
        java.time.LocalDateTime.ofInstant(
            markerExpiresAt.minus(Duration.ofDays(7)), java.time.ZoneOffset.UTC),
        java.time.LocalDateTime.ofInstant(markerExpiresAt, java.time.ZoneOffset.UTC));
  }

  @Test
  @DisplayName("보관 기한(1년)이 지난 관리자 감사 기록만 지운다")
  void auditEvents() {
    long actor =
        new com.lottery.lottery_service.account.AccountStore(jdbc, tm, clock)
            .login(com.lottery.lottery_service.auth.OAuthProvider.KAKAO, "auditor")
            .memberId();
    audit(actor, T0.plus(Duration.ofDays(365)));
    audit(actor, T0.plus(Duration.ofDays(366)));
    HousekeepingCleaner cleaner = new HousekeepingCleaner(jdbc, clock);

    clock.set(T0.plus(Duration.ofDays(365)).minusSeconds(1));
    assertThat(cleaner.deleteExpiredAuditEvents(100)).isZero();
    clock.set(T0.plus(Duration.ofDays(365)));
    assertThat(cleaner.deleteExpiredAuditEvents(100)).isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM admin_audit_event", Long.class))
        .isEqualTo(1);
  }

  private void audit(long actor, Instant expiresAt) {
    jdbc.update(
        "INSERT INTO admin_audit_event (actor_member_id, action, target_type, target_key, reason,"
            + " outcome, occurred_at, expires_at) VALUES (?, 'ADMIN_GRANT', 'MEMBER', 'x', 'r',"
            + " 'SUCCEEDED', ?, ?)",
        actor,
        java.time.LocalDateTime.ofInstant(T0, java.time.ZoneOffset.UTC),
        java.time.LocalDateTime.ofInstant(expiresAt, java.time.ZoneOffset.UTC));
  }

  @Test
  @DisplayName("성공 후 30일이 지난 job만 지우고 DEAD와 대기 중인 job은 남긴다")
  void jobs() {
    JobStore jobs = new JobStore(jdbc, tm, clock, new JobProperties(1, Duration.ofMinutes(5)));
    jobs.enqueue("A", "done", "{}", T0);
    jobs.enqueue("A", "dead", "{}", T0);
    jobs.enqueue("A", "ready", "{}", T0.plus(Duration.ofDays(100)));
    jobs.complete(jobs.claim(List.of("A")).orElseThrow());
    jobs.fail(jobs.claim(List.of("A")).orElseThrow(), "X", T0);
    jdbc.update("DELETE FROM job WHERE job_type = 'OPERATOR_ALERT'");

    clock.set(T0.plus(Duration.ofDays(30)));
    assertThat(new HousekeepingCleaner(jdbc, clock).deleteFinishedJobs()).isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM job", Long.class)).isEqualTo(2);
  }

  @Test
  @DisplayName("기록이 남지 않은 주체의 마감 지난 회차 추천 이력·요청만 지우고, 마감 전 회차는 건드리지 않는다(D-025)")
  void roundHistory() {
    long subject =
        new GuestCredentialStore(jdbc, tm, clock, TestSecrets.keys()).issue().subjectId();
    jdbc.update(
        "INSERT INTO lotto_round (round_no, sales_open_at, sales_close_at, draw_at, schedule_version)"
            + " VALUES (1199, '2026-10-01', '2026-10-05', '2026-10-05', 1), (1200, '2026-10-05', '2026-12-31', '2026-12-31', 1)");
    for (int round : List.of(1199, 1200)) {
      jdbc.update(
          "INSERT INTO recommendation_request (public_id, subject_id, round_no, key_digest, request_hash,"
              + " accepted_at, policy_version, generation_mode, filter_version, response_expires_at)"
              + " VALUES (UUID_TO_BIN(UUID()), ?, ?, ?, ?, NOW(6), 1, 'BASIC', 'FILTER_V1', NOW(6))",
          subject,
          round,
          new byte[32],
          new byte[32]);
      long request = jdbc.queryForObject("SELECT MAX(id) FROM recommendation_request", Long.class);
      jdbc.update(
          "INSERT INTO recommendation_seen (subject_id, round_no, numbers_key, first_request_id)"
              + " VALUES (?, ?, '01 02 03 04 05 06', ?)",
          subject,
          round,
          request);
    }
    HousekeepingCleaner cleaner = new HousekeepingCleaner(jdbc, clock);

    assertThat(cleaner.deleteOrphanRoundHistory(1199)).isEqualTo(1);
    assertThat(cleaner.deleteOrphanRoundHistory(1200)).isZero();
    assertThat(jdbc.queryForList("SELECT round_no FROM recommendation_seen", Integer.class))
        .containsExactly(1200);
  }

  @Test
  @DisplayName("회원의 구매 기록만 남은 회차는 추천 이력·요청·항목을 지우고, 아직 정리할 기록이 남은 회원은 남긴다(04 5절)")
  void roundHistoryWithKeptRecords() {
    AccountStore accounts = new AccountStore(jdbc, tm, clock);
    long kept = accounts.login(OAuthProvider.KAKAO, "kept").subjectId();
    long pending = accounts.login(OAuthProvider.KAKAO, "pending").subjectId();
    jdbc.update(
        "INSERT INTO lotto_round (round_no, sales_open_at, sales_close_at, draw_at, schedule_version)"
            + " VALUES (1199, '2026-10-01', '2026-10-05', '2026-10-05', 1)");
    for (long subject : List.of(kept, pending)) {
      jdbc.update(
          "INSERT INTO recommendation_request (public_id, subject_id, round_no, key_digest, request_hash,"
              + " accepted_at, policy_version, generation_mode, filter_version, response_expires_at)"
              + " VALUES (UUID_TO_BIN(UUID()), ?, 1199, ?, ?, NOW(6), 1, 'BASIC', 'FILTER_V1', NOW(6))",
          subject,
          new byte[32],
          new byte[32]);
      long request = jdbc.queryForObject("SELECT MAX(id) FROM recommendation_request", Long.class);
      boolean purchased = subject == kept;
      jdbc.update(
          "INSERT INTO lotto_record (public_id, owner_subject_id, round_no, numbers_key,"
              + " recommended_at, purchased, purchased_at) VALUES (UUID_TO_BIN(UUID()), ?, 1199,"
              + " '01 02 03 04 05 06', NOW(6), ?, "
              + (purchased ? "NOW(6)" : "NULL")
              + ")",
          subject,
          purchased);
      long record = jdbc.queryForObject("SELECT MAX(id) FROM lotto_record", Long.class);
      jdbc.update(
          "INSERT INTO recommendation_item (request_id, ordinal, record_id, numbers_key)"
              + " VALUES (?, 1, ?, '01 02 03 04 05 06')",
          request,
          record);
      jdbc.update(
          "INSERT INTO recommendation_seen (subject_id, round_no, numbers_key, first_request_id)"
              + " VALUES (?, 1199, '01 02 03 04 05 06', ?)",
          subject,
          request);
    }

    assertThat(new HousekeepingCleaner(jdbc, clock).deleteOrphanRoundHistory(1199)).isEqualTo(1);

    assertThat(jdbc.queryForList("SELECT subject_id FROM recommendation_seen", Long.class))
        .containsExactly(pending);
    assertThat(jdbc.queryForList("SELECT subject_id FROM recommendation_request", Long.class))
        .containsExactly(pending);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM recommendation_item", Long.class))
        .isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lotto_record", Long.class)).isEqualTo(2);
  }

  @Test
  @DisplayName("당첨·미구매 기록만 남은 회원은 이력을 지우고, 비회원 기록·숨긴 기록이 남거나 정리 보류 중인 회차는 남긴다(04 5절)")
  void roundHistoryHoldBranches() {
    AccountStore accounts = new AccountStore(jdbc, tm, clock);
    long winner = accounts.login(OAuthProvider.KAKAO, "winner").subjectId();
    long hidden = accounts.login(OAuthProvider.KAKAO, "hidden").subjectId();
    long guest = new GuestCredentialStore(jdbc, tm, clock, TestSecrets.keys()).issue().subjectId();
    jdbc.update(
        "INSERT INTO lotto_round (round_no, sales_open_at, sales_close_at, draw_at, schedule_version)"
            + " VALUES (1199, '2026-10-01', '2026-10-05', '2026-10-05', 1)");
    jdbc.update(
        "INSERT INTO draw_result (public_id, round_no, revision, numbers_key, bonus_number,"
            + " semantic_hash, source_hash, state, fetched_at, published_at) VALUES (UUID_TO_BIN(UUID()),"
            + " 1199, 1, '01 02 03 04 05 06', 7, ?, ?, 'VERIFIED', NOW(6), NOW(6))",
        new byte[32],
        new byte[32]);
    long result = jdbc.queryForObject("SELECT MAX(id) FROM draw_result", Long.class);
    jdbc.update("UPDATE lotto_round SET published_result_id = ? WHERE round_no = 1199", result);
    long winning = historyWithRecord(winner, false, false);
    jdbc.update(
        "INSERT INTO winning_evaluation (record_id, result_id, round_no, record_version, rank_no,"
            + " matched_count, bonus_matched, evaluated_at) VALUES (?, ?, 1199, 0, 4, 4, FALSE, NOW(6))",
        winning,
        result);
    historyWithRecord(hidden, false, true);
    historyWithRecord(guest, false, false);
    HousekeepingCleaner cleaner = new HousekeepingCleaner(jdbc, clock);

    jdbc.update("UPDATE lotto_round SET cleanup_held = TRUE WHERE round_no = 1199");
    assertThat(cleaner.deleteOrphanRoundHistory(1199)).isZero();
    jdbc.update("UPDATE lotto_round SET cleanup_held = FALSE WHERE round_no = 1199");
    assertThat(cleaner.deleteOrphanRoundHistory(1199)).isEqualTo(1);

    assertThat(jdbc.queryForList("SELECT subject_id FROM recommendation_request", Long.class))
        .containsExactlyInAnyOrder(hidden, guest);
    assertThat(jdbc.queryForList("SELECT subject_id FROM recommendation_seen", Long.class))
        .containsExactlyInAnyOrder(hidden, guest);
  }

  private long historyWithRecord(long subject, boolean purchased, boolean deleted) {
    jdbc.update(
        "INSERT INTO recommendation_request (public_id, subject_id, round_no, key_digest, request_hash,"
            + " accepted_at, policy_version, generation_mode, filter_version, response_expires_at)"
            + " VALUES (UUID_TO_BIN(UUID()), ?, 1199, ?, ?, NOW(6), 1, 'BASIC', 'FILTER_V1', NOW(6))",
        subject,
        new byte[32],
        new byte[32]);
    long request = jdbc.queryForObject("SELECT MAX(id) FROM recommendation_request", Long.class);
    jdbc.update(
        "INSERT INTO lotto_record (public_id, owner_subject_id, round_no, numbers_key,"
            + " recommended_at, purchased, purchased_at, deleted_at) VALUES (UUID_TO_BIN(UUID()), ?,"
            + " 1199, '01 02 03 04 20 30', NOW(6), ?, "
            + (purchased ? "NOW(6)" : "NULL")
            + ", "
            + (deleted ? "NOW(6)" : "NULL")
            + ")",
        subject,
        purchased);
    long record = jdbc.queryForObject("SELECT MAX(id) FROM lotto_record", Long.class);
    jdbc.update(
        "INSERT INTO recommendation_item (request_id, ordinal, record_id, numbers_key)"
            + " VALUES (?, 1, ?, '01 02 03 04 20 30')",
        request,
        record);
    jdbc.update(
        "INSERT INTO recommendation_seen (subject_id, round_no, numbers_key, first_request_id)"
            + " VALUES (?, 1199, '01 02 03 04 20 30', ?)",
        subject,
        request);
    return record;
  }

  @Test
  @DisplayName("최종 상태 뒤 30일이 지난 메시지만 본문·수신자 사본을 비우고, UNKNOWN과 보낼 메시지는 남기며 상태·비용·안내 요약은 그대로 둔다")
  void messageCopies() {
    jdbc.update(
        "INSERT INTO lotto_round (round_no, sales_open_at, sales_close_at, draw_at, schedule_version)"
            + " VALUES (1200, '2026-10-24', '2026-10-31 11:00:00', '2026-10-31 11:35:00', 1)");
    long delivered = message("a", "DELIVERED", "2026-11-01 00:00:00", "2026-11-01 00:00:00");
    long unknown = message("b", "UNKNOWN", null, "2026-11-01 00:00:00");
    long retry = message("c", "RETRY_WAIT", null, "2026-11-01 00:00:00");
    long recent = message("d", "FAILED", "2026-11-20 00:00:00", "2026-11-20 00:00:00");
    HousekeepingCleaner cleaner = new HousekeepingCleaner(jdbc, clock);

    clock.set(Instant.parse("2026-12-01T00:00:00Z").minusSeconds(1));
    assertThat(cleaner.clearMessageCopies(100)).isZero();
    clock.set(Instant.parse("2026-12-01T00:00:00Z"));
    assertThat(cleaner.clearMessageCopies(100)).isEqualTo(1);
    assertThat(cleaner.clearMessageCopies(100)).isZero();

    assertThat(
            jdbc.queryForMap(
                "SELECT state, payload_ciphertext, recipient_ciphertext, payload_hash,"
                    + " CAST(communicated_summary AS CHAR) AS summary FROM delivery_message"
                    + " WHERE id = ?",
                delivered))
        .containsEntry("state", "DELIVERED")
        .containsEntry("payload_ciphertext", null)
        .containsEntry("recipient_ciphertext", null)
        .containsEntry("payload_hash", null)
        .containsEntry("summary", "{\"items\": [], \"resultId\": 1}");
    for (long kept : List.of(unknown, retry, recent)) {
      assertThat(
              jdbc.queryForObject(
                  "SELECT payload_ciphertext IS NOT NULL FROM delivery_message WHERE id = ?",
                  Boolean.class,
                  kept))
          .isTrue();
    }
  }

  @Test
  @DisplayName("90일이 지났고 그날·그달·그 회차 집계가 확정된 사건만 지우고, 하나라도 미확정이면 남기며, 확정 집계 값은 그대로다(T21 원천 정리)")
  void usageEvents() {
    jdbc.update(
        "INSERT INTO lotto_round (round_no, sales_open_at, sales_close_at, draw_at, schedule_version)"
            + " VALUES (1200, '2026-10-24', '2026-10-31 11:00:00', '2026-10-31 11:35:00', 1)");
    long subject =
        new GuestCredentialStore(jdbc, tm, clock, TestSecrets.keys()).issue().subjectId();
    UsageEventStore events = new UsageEventStore(jdbc);
    events.record(subject, null, "RECOMMENDATION_SETS", 5, T0);
    events.record(subject, 1200, "RECOMMENDATION_SETS", 5, T0);
    events.record(subject, null, "RECOMMENDATION_SETS", 5, T0.plus(Duration.ofDays(1)));
    aggregate("DAY", "2026-11-01", 10);
    aggregate("MONTH", "2026-11", 15);
    aggregate("ROUND", "1200", 5);
    HousekeepingCleaner cleaner = new HousekeepingCleaner(jdbc, clock);

    clock.set(T0.plus(Duration.ofDays(90)).minusSeconds(1));
    assertThat(cleaner.deleteAggregatedUsageEvents(100)).isZero();
    clock.set(T0.plus(Duration.ofDays(91)));
    jdbc.update("UPDATE usage_aggregate SET state = 'BUILDING' WHERE period_type = 'ROUND'");
    assertThat(cleaner.deleteAggregatedUsageEvents(100)).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM usage_event WHERE round_no = 1200", Long.class))
        .isEqualTo(1);
    jdbc.update("UPDATE usage_aggregate SET state = 'VERIFIED' WHERE period_type = 'ROUND'");
    jdbc.update("UPDATE usage_aggregate SET state = 'BUILDING' WHERE period_type = 'DAY'");
    assertThat(cleaner.deleteAggregatedUsageEvents(100)).isZero();
    jdbc.update("UPDATE usage_aggregate SET state = 'VERIFIED' WHERE period_type = 'DAY'");
    assertThat(cleaner.deleteAggregatedUsageEvents(100)).isEqualTo(1);

    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM usage_event", Long.class)).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT value_count FROM usage_aggregate WHERE period_type = 'MONTH'", Long.class))
        .isEqualTo(15);
  }

  private void aggregate(String type, String key, long value) {
    jdbc.update(
        "INSERT INTO usage_aggregate (period_type, period_key, metric, dimension_key,"
            + " calculation_version, value_count, as_of, state) VALUES (?, ?, 'RECOMMENDATION_SETS',"
            + " 'ALL', 1, ?, NOW(6), 'VERIFIED')",
        type,
        key,
        value);
  }

  @Test
  @DisplayName("인증 코드 요청은 사용·만료 후 30일이 지나면 대상 암호문만 비우고 행·상태·HMAC은 남긴다(2026-11-09 Q21)")
  void challengeTargets() {
    long member = new AccountStore(jdbc, tm, clock).login(OAuthProvider.KAKAO, "v").memberId();
    long consumed = challenge(member, "CONSUMED", "2026-11-01 00:05:00", "2026-11-01 00:02:00");
    long expired = challenge(member, "EXPIRED", "2026-11-01 00:05:00", null);
    long recent = challenge(member, "CONSUMED", "2026-11-10 00:05:00", "2026-11-10 00:02:00");
    HousekeepingCleaner cleaner = new HousekeepingCleaner(jdbc, clock);

    clock.set(Instant.parse("2026-12-01T00:02:00Z").minusSeconds(1));
    assertThat(cleaner.clearChallengeTargets(100)).isZero();
    clock.set(Instant.parse("2026-12-01T00:05:00Z"));
    assertThat(cleaner.clearChallengeTargets(100)).isEqualTo(2);
    assertThat(cleaner.clearChallengeTargets(100)).isZero();

    assertThat(
            jdbc.queryForList(
                "SELECT id FROM verification_challenge WHERE target_ciphertext IS NULL ORDER BY id",
                Long.class))
        .containsExactly(consumed, expired);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM verification_challenge WHERE target_hmac IS NOT NULL",
                Long.class))
        .isEqualTo(3);
    assertThat(
            jdbc.queryForObject(
                "SELECT target_ciphertext IS NOT NULL FROM verification_challenge WHERE id = ?",
                Boolean.class,
                recent))
        .isTrue();
  }

  private long challenge(long member, String state, String expiresAt, String consumedAt) {
    jdbc.update(
        "INSERT INTO verification_challenge (public_id, member_id, target_hmac, target_ciphertext,"
            + " key_version, expected_contact_version, state, expires_at, consumed_at)"
            + " VALUES (UUID_TO_BIN(UUID()), ?, ?, ?, 1, 0, ?, ?, ?)",
        member,
        new byte[32],
        new byte[] {7},
        state,
        expiresAt,
        consumedAt);
    return jdbc.queryForObject("SELECT MAX(id) FROM verification_challenge", Long.class);
  }

  private long message(String login, String state, String finalizedAt, String lastAttemptAt) {
    long member = new AccountStore(jdbc, tm, clock).login(OAuthProvider.KAKAO, login).memberId();
    jdbc.update(
        "INSERT INTO delivery_message (public_id, purpose, member_id, round_no, state,"
            + " provider_request_key, payload_ciphertext, recipient_ciphertext, payload_hash,"
            + " communicated_summary, last_attempt_at, finalized_at, expires_at)"
            + " VALUES (UUID_TO_BIN(UUID()), 'RESULT', ?, 1200, ?, UUID_TO_BIN(UUID()), ?, ?, ?,"
            + " '{\"resultId\": 1, \"items\": []}', ?, ?, '2026-11-08 00:00:00')",
        member,
        state,
        new byte[] {9},
        new byte[] {8},
        new byte[32],
        lastAttemptAt,
        finalizedAt);
    return jdbc.queryForObject("SELECT MAX(id) FROM delivery_message", Long.class);
  }
}
