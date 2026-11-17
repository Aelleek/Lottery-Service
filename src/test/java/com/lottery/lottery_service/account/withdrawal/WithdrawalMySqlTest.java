package com.lottery.lottery_service.account.withdrawal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lottery.lottery_service.account.AccountStore;
import com.lottery.lottery_service.account.GuestCredentialStore;
import com.lottery.lottery_service.account.MemberLogin;
import com.lottery.lottery_service.account.SubjectClaimStore;
import com.lottery.lottery_service.auth.OAuthProvider;
import com.lottery.lottery_service.common.command.CommandOperationStore;
import com.lottery.lottery_service.common.command.CommandTokenCodec;
import com.lottery.lottery_service.common.error.BusinessException;
import com.lottery.lottery_service.common.error.ErrorCode;
import com.lottery.lottery_service.common.job.JobProperties;
import com.lottery.lottery_service.common.job.JobRunner;
import com.lottery.lottery_service.common.job.JobState;
import com.lottery.lottery_service.common.job.JobStore;
import com.lottery.lottery_service.notification.ProviderStatus;
import com.lottery.lottery_service.notification.UnknownMessageReconciler;
import com.lottery.lottery_service.notification.budget.BudgetProperties;
import com.lottery.lottery_service.notification.budget.MessageBudgetStore;
import com.lottery.lottery_service.notification.budget.MessagePurpose;
import com.lottery.lottery_service.record.HistoryImportStore;
import com.lottery.lottery_service.stats.UsageEventStore;
import com.lottery.lottery_service.support.MutableClock;
import com.lottery.lottery_service.support.MySqlContainerSupport;
import com.lottery.lottery_service.support.TestSecrets;
import java.nio.file.Files;
import java.nio.file.Path;
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
import org.junit.jupiter.api.io.TempDir;
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
            "SPRING_SESSION",
            "job",
            "usage_event",
            "message_budget",
            "delivery_message",
            "verification_challenge",
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
  @DisplayName("탈퇴 삭제는 가져오기 항목·인증 요청·인증 메시지까지 지우고, 다른 회원의 기록·연락처·인증 요청은 그대로 둔다")
  void withdrawalDeletesOnlyThatMembersData() {
    long claim = jdbc.queryForObject("SELECT id FROM subject_claim", Long.class);
    long guestRecord =
        jdbc.queryForObject(
            "SELECT id FROM lotto_record WHERE owner_subject_id = ?", Long.class, guest);
    long memberRecord =
        jdbc.queryForObject(
            "SELECT id FROM lotto_record WHERE owner_subject_id = ?",
            Long.class,
            member.subjectId());
    jdbc.update(
        "INSERT INTO history_import_item (claim_id, source_record_id, target_record_id, outcome)"
            + " VALUES (?, ?, ?, 'MERGED')",
        claim,
        guestRecord,
        memberRecord);
    MemberLogin other = new AccountStore(jdbc, tm, clock).login(OAuthProvider.KAKAO, "stayer");
    long otherGuest =
        new GuestCredentialStore(jdbc, tm, clock, TestSecrets.keys()).issue().subjectId();
    jdbc.update(
        "INSERT INTO lotto_record (public_id, owner_subject_id, round_no, numbers_key, recommended_at)"
            + " VALUES (UUID_TO_BIN(UUID()), ?, ?, '01 02 03 04 05 09', NOW(6))",
        otherGuest,
        ROUND);
    long otherGuestRecord = jdbc.queryForObject("SELECT MAX(id) FROM lotto_record", Long.class);
    new SubjectClaimStore(jdbc, tm, clock).claim(otherGuest, other.subjectId());
    long otherClaim =
        jdbc.queryForObject(
            "SELECT id FROM subject_claim WHERE member_subject_id = ?",
            Long.class,
            other.subjectId());
    jdbc.update(
        "INSERT INTO history_import_item (claim_id, source_record_id, outcome)"
            + " VALUES (?, ?, 'SKIPPED_SOURCE_EXPIRED')",
        otherClaim,
        otherGuestRecord);
    jdbc.update(
        "INSERT INTO lotto_record (public_id, owner_subject_id, round_no, numbers_key, purchased,"
            + " purchased_at) VALUES (UUID_TO_BIN(UUID()), ?, ?, '01 02 03 04 05 06', TRUE, NOW(6))",
        other.subjectId(),
        ROUND);
    jdbc.update(
        "INSERT INTO member_contact (member_id, address_ciphertext, key_version, verified_at)"
            + " VALUES (?, ?, 1, NOW(6))",
        other.memberId(),
        new byte[] {4});
    for (long owner : List.of(member.memberId(), other.memberId())) {
      jdbc.update(
          "INSERT INTO verification_challenge (public_id, member_id, target_hmac, key_version,"
              + " expected_contact_version, state, expires_at)"
              + " VALUES (UUID_TO_BIN(UUID()), ?, ?, 1, 0, 'CONSUMED', NOW(6))",
          owner,
          new byte[32]);
      long challenge =
          jdbc.queryForObject("SELECT MAX(id) FROM verification_challenge", Long.class);
      jdbc.update(
          "INSERT INTO delivery_message (public_id, purpose, member_id, challenge_id, state,"
              + " provider_request_key, expires_at) VALUES (UUID_TO_BIN(UUID()), 'VERIFICATION', ?,"
              + " ?, 'ACCEPTED', UUID_TO_BIN(UUID()), NOW(6))",
          owner,
          challenge);
    }

    new WithdrawalService(jdbc, tm, jobs, clock).block(member.memberId());
    assertThat(runner(journal()).runOnce()).contains(JobState.SUCCEEDED);

    assertThat(jdbc.queryForList("SELECT claim_id FROM history_import_item", Long.class))
        .containsExactly(otherClaim);
    for (String table : List.of("verification_challenge", "member_contact")) {
      assertThat(jdbc.queryForList("SELECT member_id FROM " + table, Long.class))
          .as(table)
          .containsExactly(other.memberId());
    }
    assertThat(
            jdbc.queryForList(
                "SELECT member_id FROM delivery_message WHERE purpose = 'VERIFICATION'",
                Long.class))
        .containsExactly(other.memberId());
    assertThat(jdbc.queryForList("SELECT owner_subject_id FROM lotto_record", Long.class))
        .containsExactlyInAnyOrder(other.subjectId(), otherGuest);
    assertThat(state("member", other.memberId())).isEqualTo("ACTIVE");
  }

  @Test
  @DisplayName("local·test용 파일 삭제 기록으로 탈퇴 흐름이 끝까지 돌고 파일에 회원 외부 ID가 남는다(D-052)")
  void withdrawalWithLocalFileJournal(@TempDir Path dir) throws Exception {
    Path file = dir.resolve("journal.jsonl");
    new WithdrawalService(jdbc, tm, jobs, clock).block(member.memberId());

    assertThat(runner(new LocalFileDeletionJournal(file)).runOnce()).contains(JobState.SUCCEEDED);

    assertThat(state("member", member.memberId())).isEqualTo("WITHDRAWN");
    assertThat(Files.readString(file)).contains(member.memberPublicId().toString());
  }

  @Test
  @DisplayName("복원 뒤 삭제 기록을 다시 적용하면 되살아난 탈퇴 회원을 다시 막고 지우며, 모든 세션을 회수한다(07 4절)")
  void replayAfterRestore(@TempDir Path dir) throws Exception {
    LocalFileDeletionJournal journal = new LocalFileDeletionJournal(dir.resolve("journal.jsonl"));
    journal.append(member.memberPublicId(), clock.instant());
    journal.append(UUID.randomUUID(), clock.instant());
    RestoreReplay replay = new RestoreReplay(jdbc, new WithdrawalService(jdbc, tm, jobs, clock));

    RestoreReplay.Result result = replay.replay(journal);

    assertThat(result).isEqualTo(new RestoreReplay.Result(1, 0, 1));
    assertThat(state("member", member.memberId())).isEqualTo("WITHDRAWING");
    RestoreWithdrawalFinisher finisher =
        new RestoreWithdrawalFinisher(
            jdbc,
            jobs,
            new WithdrawalJobHandler(jdbc, journal, budget, jobs, tm, clock),
            tm,
            clock);
    assertThat(finisher.finish()).isZero();
    assertThat(state("member", member.memberId())).isEqualTo("WITHDRAWN");
    assertThat(replay.replay(journal)).isEqualTo(new RestoreReplay.Result(0, 2, 1));

    long before = jdbc.queryForObject("SELECT SUM(auth_version) FROM member", Long.class);
    assertThat(replay.revokeAllSessions()).isPositive();
    assertThat(jdbc.queryForObject("SELECT SUM(auth_version) FROM member", Long.class))
        .isGreaterThan(before);
  }

  @Test
  @DisplayName("복원 명령 안에서 삭제 기록 저장소가 멈춰 삭제를 끝내지 못하면 남은 삭제 수를 돌려준다")
  void finisherReportsUnfinished() {
    new WithdrawalService(jdbc, tm, jobs, clock).block(member.memberId());
    RestoreWithdrawalFinisher finisher =
        new RestoreWithdrawalFinisher(
            jdbc,
            jobs,
            new WithdrawalJobHandler(
                jdbc, new UnavailableDeletionJournal(), budget, jobs, tm, clock),
            tm,
            clock);

    assertThat(finisher.finish()).isEqualTo(1);
    assertThat(state("member", member.memberId())).isEqualTo("WITHDRAWING");
  }

  @Test
  @DisplayName("탈퇴를 접수하면 그 회원의 모든 세션을 바로 지우고, 기다리는 가져오기 job은 취소한다. 다른 회원 것은 그대로다")
  void blockRevokesSessionsAndImports() {
    String publicId =
        jdbc.queryForObject(
            "SELECT BIN_TO_UUID(public_id) FROM member WHERE id = ?",
            String.class,
            member.memberId());
    for (String[] row :
        List.of(
            new String[] {"s1", publicId},
            new String[] {"s2", publicId},
            new String[] {"s3", "other"})) {
      jdbc.update(
          "INSERT INTO SPRING_SESSION (primary_id, session_id, creation_time, last_access_time,"
              + " max_inactive_interval, expiry_time, principal_name) VALUES (?, ?, 0, 0, 60, ?, ?)",
          row[0],
          row[0] + "-id",
          Long.MAX_VALUE,
          row[1]);
    }
    jobs.enqueue(
        "HISTORY_IMPORT",
        "claim:1",
        "{\"claimId\":1,\"memberSubjectId\":" + member.subjectId() + "}",
        clock.instant());
    jobs.enqueue(
        "HISTORY_IMPORT", "claim:2", "{\"claimId\":2,\"memberSubjectId\":-1}", clock.instant());

    new WithdrawalService(jdbc, tm, jobs, clock).block(member.memberId());

    assertThat(
            jdbc.queryForList(
                "SELECT primary_id FROM SPRING_SESSION ORDER BY primary_id", String.class))
        .containsExactly("s3");
    assertThat(
            jdbc.queryForList(
                "SELECT state FROM job WHERE job_type = 'HISTORY_IMPORT' ORDER BY id",
                String.class))
        .containsExactly("CANCELED", "READY");
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
  @DisplayName("기록이 한 번에 지우는 수(500)보다 많아도 탈퇴 삭제가 회원과 귀속한 비회원의 기록을 모두 지운다")
  void withdrawalDeletesRecordsBeyondOneBatch() {
    List<Object[]> rows = new ArrayList<>();
    for (int a = 10; a <= 43; a++) {
      for (int b = a + 1; b <= 44; b++) {
        for (int c = b + 1; c <= 45; c++) {
          if (rows.size() < WithdrawalJobHandler.RECORD_BATCH + 10) {
            rows.add(
                new Object[] {
                  member.subjectId(), ROUND, String.format("01 02 03 %02d %02d %02d", a, b, c)
                });
          }
        }
      }
    }
    jdbc.batchUpdate(
        "INSERT INTO lotto_record (public_id, owner_subject_id, round_no, numbers_key,"
            + " recommended_at) VALUES (UUID_TO_BIN(UUID()), ?, ?, ?, NOW(6))",
        rows);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lotto_record", Long.class))
        .isGreaterThan(WithdrawalJobHandler.RECORD_BATCH);
    new WithdrawalService(jdbc, tm, jobs, clock).block(member.memberId());

    assertThat(runner(journal()).runOnce()).contains(JobState.SUCCEEDED);

    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lotto_record", Long.class)).isZero();
    assertThat(state("member", member.memberId())).isEqualTo("WITHDRAWN");
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
  @DisplayName(
      "탈퇴 때 제공자 메시지 ID가 있는 UNKNOWN은 UNKNOWN으로 남아 ID로 계속 대조되고, 개인 자료는 지워지며 새 메시지는 생기지 않는다(T56)")
  void unknownWithProviderIdKeepsReconciling() {
    jdbc.update("UPDATE delivery_message SET provider_message_id = 'M-56' WHERE id = ?", message);
    new WithdrawalService(jdbc, tm, jobs, clock).block(member.memberId());
    assertThat(runner(journal()).runOnce()).contains(JobState.SUCCEEDED);

    assertThat(
            jdbc.queryForMap(
                "SELECT state, cost_state, provider_message_id, payload_ciphertext,"
                    + " recipient_ciphertext FROM delivery_message"))
        .containsEntry("state", "UNKNOWN")
        .containsEntry("cost_state", "RESERVED")
        .containsEntry("provider_message_id", "M-56")
        .containsEntry("payload_ciphertext", null)
        .containsEntry("recipient_ciphertext", null);

    UnknownMessageReconciler reconciler = new UnknownMessageReconciler(jdbc, budget, jobs, clock);
    String after =
        new TransactionTemplate(tm)
            .execute(s -> reconciler.apply(message, new ProviderStatus.Accepted("M-56")));

    assertThat(after).isEqualTo("ACCEPTED");
    assertThat(jdbc.queryForMap("SELECT state, cost_state FROM delivery_message"))
        .containsEntry("state", "ACCEPTED")
        .containsEntry("cost_state", "SPENT");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM delivery_message", Long.class))
        .isEqualTo(1);
    assertThat(budget.matchesMessages(LocalDate.parse("2026-11-01"))).isTrue();
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

  @Test
  @DisplayName(
      "탈퇴 명령은 최근 5분 재인증이 없으면 REAUTH_REQUIRED, 있으면 접근 차단과 삭제 job 접수, 같은 요청을 다시 보내면 같은 접수 결과")
  void withdrawCommand() {
    CommandTokenCodec codec = new CommandTokenCodec(TestSecrets.keys(), clock);
    WithdrawCommandService commands =
        new WithdrawCommandService(
            codec,
            new CommandOperationStore(jdbc, tm, clock),
            new WithdrawalService(jdbc, tm, jobs, clock),
            jdbc,
            tm,
            clock);
    String token =
        codec.issue(member.subjectPublicId(), WithdrawCommandService.OPERATION, null, null);
    UUID key = UUID.randomUUID();
    jdbc.update("DELETE FROM command_operation");

    assertThatThrownBy(
            () ->
                commands.withdraw(
                    member.memberId(),
                    member.subjectId(),
                    member.subjectPublicId(),
                    token,
                    key,
                    0,
                    clock.instant().minus(Duration.ofMinutes(6))))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.getCode()).isEqualTo(ErrorCode.REAUTH_REQUIRED));
    assertThat(state("member", member.memberId())).isEqualTo("ACTIVE");

    Instant reauth = clock.instant().minus(Duration.ofMinutes(4));
    UUID first =
        commands.withdraw(
            member.memberId(), member.subjectId(), member.subjectPublicId(), token, key, 0, reauth);
    UUID again =
        commands.withdraw(
            member.memberId(), member.subjectId(), member.subjectPublicId(), token, key, 0, reauth);

    assertThat(again).isEqualTo(first);
    assertThat(state("member", member.memberId())).isEqualTo("WITHDRAWING");
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM job WHERE job_type = ?", Long.class, WithdrawalService.JOB))
        .isEqualTo(1);
  }

  @Test
  @DisplayName("탈퇴를 접수한 뒤나 삭제가 끝난 뒤에 가져오기 묶음이 돌아도 NOT_FOUND이고 기록을 되살리지 않는다(T22 일부)")
  void lateImportAfterWithdrawal() {
    long claim = jdbc.queryForObject("SELECT id FROM subject_claim", Long.class);
    HistoryImportStore imports = new HistoryImportStore(jdbc, jobs);
    TransactionTemplate tx = new TransactionTemplate(tm);
    new WithdrawalService(jdbc, tm, jobs, clock).block(member.memberId());

    assertImportNotFound(tx, imports, claim);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM lotto_record WHERE owner_subject_id = ?",
                Long.class,
                member.subjectId()))
        .isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM history_import_item", Long.class))
        .isZero();

    assertThat(runner(journal()).runOnce()).contains(JobState.SUCCEEDED);
    assertImportNotFound(tx, imports, claim);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lotto_record", Long.class)).isZero();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM history_import_item", Long.class))
        .isZero();
  }

  private void assertImportNotFound(
      TransactionTemplate tx, HistoryImportStore imports, long claim) {
    assertThatThrownBy(
            () -> tx.execute(s -> imports.importBatch(member.subjectId(), claim, 0, 200)))
        .isInstanceOfSatisfying(
            BusinessException.class, e -> assertThat(e.getCode()).isEqualTo(ErrorCode.NOT_FOUND));
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
