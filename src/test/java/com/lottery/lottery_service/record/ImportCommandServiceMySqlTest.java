package com.lottery.lottery_service.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lottery.lottery_service.account.AccountStore;
import com.lottery.lottery_service.account.GuestCredentialStore;
import com.lottery.lottery_service.account.MemberLogin;
import com.lottery.lottery_service.account.SubjectClaimStore;
import com.lottery.lottery_service.auth.OAuthProvider;
import com.lottery.lottery_service.common.command.CommandOperationStore;
import com.lottery.lottery_service.common.command.CommandTokenCodec;
import com.lottery.lottery_service.common.command.OperationQuery;
import com.lottery.lottery_service.common.error.BusinessException;
import com.lottery.lottery_service.common.error.ErrorCode;
import com.lottery.lottery_service.common.job.JobProperties;
import com.lottery.lottery_service.common.job.JobRunner;
import com.lottery.lottery_service.common.job.JobStore;
import com.lottery.lottery_service.common.number.LottoNumbers;
import com.lottery.lottery_service.recommendation.CommandInProgressException;
import com.lottery.lottery_service.round.RoundStore;
import com.lottery.lottery_service.round.domain.RoundSchedule;
import com.lottery.lottery_service.support.MutableClock;
import com.lottery.lottery_service.support.MySqlContainerSupport;
import com.lottery.lottery_service.support.TestSecrets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/** 가져오기 명령이 job을 등록하고, job이 끝나기 전에는 처리 중, 끝나면 건수를 주는지 MySQL에서 확인한다(D-058). */
class ImportCommandServiceMySqlTest extends MySqlContainerSupport {

  private static final int OPEN = 1320;

  private static DataSource dataSource;

  private final MutableClock clock = new MutableClock(Instant.parse("2026-11-08T01:00:00Z"));
  private JdbcTemplate jdbc;
  private DataSourceTransactionManager tm;
  private JobStore jobs;
  private HistoryImportJobHandler handler;
  private ImportCommandService imports;
  private CommandTokenCodec codec;
  private MemberLogin member;
  private UUID claimPublicId;
  private long guest;

  @BeforeAll
  static void migrate() throws Exception {
    dataSource = migratedDatabase("import_command");
  }

  @BeforeEach
  void setUp() {
    jdbc = new JdbcTemplate(dataSource);
    tm = new DataSourceTransactionManager(dataSource);
    for (String t :
        List.of(
            "job",
            "command_operation",
            "history_import_item",
            "lotto_record",
            "subject_claim",
            "oauth_account",
            "guest_credential",
            "actor_subject",
            "member",
            "lotto_round")) {
      jdbc.update("DELETE FROM " + t);
    }
    RoundStore rounds = new RoundStore(jdbc, tm);
    Instant now = rounds.dbNow();
    rounds.register(
        new RoundSchedule(
            OPEN,
            now.minus(Duration.ofDays(1)),
            now.plus(Duration.ofDays(6)),
            now.plus(Duration.ofDays(6)),
            1));
    jobs = new JobStore(jdbc, tm, clock, new JobProperties(null, null));
    handler = new HistoryImportJobHandler(new HistoryImportStore(jdbc, jobs), jobs, clock);
    codec = new CommandTokenCodec(TestSecrets.keys(), clock);
    imports =
        new ImportCommandService(
            codec, new CommandOperationStore(jdbc, tm, clock), handler, jdbc, tm);
    guest = new GuestCredentialStore(jdbc, tm, clock, TestSecrets.keys()).issue().subjectId();
    member = new AccountStore(jdbc, tm, clock).login(OAuthProvider.KAKAO, "importer");
    guestRecord(LottoNumbers.of(1, 2, 3, 4, 5, 6).key());
    guestRecord(LottoNumbers.of(1, 2, 3, 4, 5, 7).key());
    jdbc.update(
        "INSERT INTO lotto_record (public_id, owner_subject_id, round_no, numbers_key, purchased,"
            + " purchased_at) VALUES (UUID_TO_BIN(UUID()), ?, ?, ?, TRUE, NOW(6))",
        member.subjectId(),
        OPEN,
        LottoNumbers.of(1, 2, 3, 4, 5, 7).key());
    new SubjectClaimStore(jdbc, tm, clock).claim(guest, member.subjectId());
    claimPublicId =
        jdbc.queryForObject(
            "SELECT BIN_TO_UUID(public_id) FROM subject_claim WHERE guest_subject_id = ?",
            UUID.class,
            guest);
  }

  @Test
  @DisplayName("시작하면 명령은 끝나고 job이 돌기 전 조회는 처리 중, 돈 뒤에는 만든 수·합친 수를 준다. 다시 시작해도 job은 하나다")
  void startAndPoll() {
    String token =
        codec.issue(member.subjectPublicId(), ImportCommandService.OPERATION, null, null);
    UUID operation =
        imports.start(
            member.subjectId(), member.subjectPublicId(), token, UUID.randomUUID(), claimPublicId);
    OperationQuery query = new OperationQuery(jdbc, List.of(imports));

    assertThatThrownBy(() -> query.find(member.subjectId(), operation))
        .isInstanceOf(CommandInProgressException.class);

    new JobRunner(jobs, List.of(handler), tm, clock).runOnce();
    OperationQuery.Outcome done = query.find(member.subjectId(), operation);
    ImportCommandService.ImportResult result =
        (ImportCommandService.ImportResult) ((OperationQuery.Outcome.Done) done).data();
    assertThat(result.outcome()).isEqualTo("COMPLETE");
    assertThat(result.createdCount()).isEqualTo(1);
    assertThat(result.mergedCount()).isEqualTo(1);
    assertThat(result.lateImportRounds()).isEmpty();

    imports.start(
        member.subjectId(), member.subjectPublicId(), token, UUID.randomUUID(), claimPublicId);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM job WHERE job_type = 'HISTORY_IMPORT'", Long.class))
        .isEqualTo(1);
  }

  @Test
  @DisplayName("가져오기 job이 DEAD로 끝나면 결과는 FAILED다")
  void deadJobIsFailed() {
    String token =
        codec.issue(member.subjectPublicId(), ImportCommandService.OPERATION, null, null);
    UUID operation =
        imports.start(
            member.subjectId(), member.subjectPublicId(), token, UUID.randomUUID(), claimPublicId);
    jdbc.update("UPDATE job SET state = 'DEAD' WHERE job_type = 'HISTORY_IMPORT'");

    OperationQuery.Outcome done =
        new OperationQuery(jdbc, List.of(imports)).find(member.subjectId(), operation);
    assertThat(
            ((ImportCommandService.ImportResult) ((OperationQuery.Outcome.Done) done).data())
                .outcome())
        .isEqualTo("FAILED");
  }

  @Test
  @DisplayName("다른 회원의 귀속이나 없는 귀속은 NOT_FOUND이고 job을 만들지 않는다")
  void otherClaimNotFound() {
    MemberLogin other = new AccountStore(jdbc, tm, clock).login(OAuthProvider.KAKAO, "other");
    String token = codec.issue(other.subjectPublicId(), ImportCommandService.OPERATION, null, null);

    for (UUID claim : List.of(claimPublicId, UUID.randomUUID())) {
      assertThatThrownBy(
              () ->
                  imports.start(
                      other.subjectId(), other.subjectPublicId(), token, UUID.randomUUID(), claim))
          .isInstanceOfSatisfying(
              BusinessException.class, e -> assertThat(e.getCode()).isEqualTo(ErrorCode.NOT_FOUND));
    }
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM job", Long.class)).isZero();
  }

  private void guestRecord(String key) {
    jdbc.update(
        "INSERT INTO lotto_record (public_id, owner_subject_id, round_no, numbers_key,"
            + " recommended_at, created_at, expires_at) VALUES (UUID_TO_BIN(UUID()), ?, ?, ?,"
            + " NOW(6), NOW(6), NOW(6) + INTERVAL 30 DAY)",
        guest,
        OPEN,
        key);
  }
}
