package com.lottery.lottery_service.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lottery.lottery_service.account.AccountStore;
import com.lottery.lottery_service.account.MemberLogin;
import com.lottery.lottery_service.auth.OAuthProvider;
import com.lottery.lottery_service.common.command.CommandOperationStore;
import com.lottery.lottery_service.common.command.CommandTokenCodec;
import com.lottery.lottery_service.common.command.OperationQuery;
import com.lottery.lottery_service.common.db.DbValues;
import com.lottery.lottery_service.common.error.BusinessException;
import com.lottery.lottery_service.common.error.ErrorCode;
import com.lottery.lottery_service.common.job.JobProperties;
import com.lottery.lottery_service.common.job.JobStore;
import com.lottery.lottery_service.common.number.LottoNumbers;
import com.lottery.lottery_service.round.RoundStore;
import com.lottery.lottery_service.round.domain.RoundSchedule;
import com.lottery.lottery_service.stats.UsageEventStore;
import com.lottery.lottery_service.support.MutableClock;
import com.lottery.lottery_service.support.MySqlContainerSupport;
import com.lottery.lottery_service.support.TestSecrets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 구매 등록 취소 명령의 멱등 처리를 MySQL에서 확인한다. */
class CancelCommandServiceMySqlTest extends MySqlContainerSupport {

  private static final int ROUND = 1200;
  private static final LottoNumbers A = LottoNumbers.of(1, 2, 3, 4, 5, 6);

  private static DataSource dataSource;

  private final MutableClock clock = new MutableClock(Instant.parse("2026-11-08T01:00:00Z"));
  private JdbcTemplate jdbc;
  private TransactionTemplate tx;
  private PurchaseStore purchases;
  private CommandTokenCodec codec;
  private CancelCommandService commands;
  private MemberLogin member;

  @BeforeAll
  static void migrate() throws Exception {
    dataSource = migratedDatabase("cancel_command");
  }

  @BeforeEach
  void setUp() {
    jdbc = new JdbcTemplate(dataSource);
    for (String table :
        List.of(
            "job",
            "usage_event",
            "command_operation",
            "lotto_record",
            "oauth_account",
            "actor_subject",
            "member",
            "lotto_round")) {
      jdbc.update("DELETE FROM " + table);
    }
    DataSourceTransactionManager tm = new DataSourceTransactionManager(dataSource);
    tx = new TransactionTemplate(tm);
    RoundStore rounds = new RoundStore(jdbc, tm);
    Instant now = rounds.dbNow();
    rounds.register(
        new RoundSchedule(
            ROUND, now.minus(Duration.ofDays(1)), now.plusSeconds(3600), now.plusSeconds(7200), 1));
    purchases =
        new PurchaseStore(
            jdbc,
            rounds,
            new UsageEventStore(jdbc),
            new JobStore(jdbc, tm, clock, new JobProperties(null, null)));
    codec = new CommandTokenCodec(TestSecrets.keys(), clock);
    commands =
        new CancelCommandService(
            codec, new CommandOperationStore(jdbc, tm, clock), purchases, jdbc, tm);
    member = new AccountStore(jdbc, tm, clock).login(OAuthProvider.KAKAO, "m");
  }

  @Test
  @DisplayName("직접 등록 기록 삭제 뒤 같은 명령을 다시 보내면 번호 없는 같은 완료 증명이고, 그 사이 다시 등록한 구매는 취소하지 않는다(T31)")
  void replayAfterRepurchase() {
    UUID record = register();
    String token =
        codec.issue(member.subjectPublicId(), CancelCommandService.OPERATION, null, null);
    UUID key = UUID.randomUUID();

    CancelCommandService.CancelProof first = cancel(token, key, record, 0);
    assertThat(first.outcome()).isEqualTo(CancelCommandService.DELETED);
    assertThat(row(record).get("deleted_at")).isNotNull();

    clock.set(clock.instant().plusSeconds(60));
    assertThat(register()).isEqualTo(record);
    long versionAfterRebuy = ((Number) row(record).get("version")).longValue();

    CancelCommandService.CancelProof again = cancel(token, key, record, 0);

    assertThat(again).isEqualTo(first);
    assertThat(row(record))
        .containsEntry("purchased", true)
        .containsEntry("deleted_at", null)
        .containsEntry("version", versionAfterRebuy);
  }

  @Test
  @DisplayName("추천 기록의 구매 취소는 기록을 남기고, 옛 취소를 다시 보내도 다시 산 구매 표시는 그대로다(T11)")
  void recommendationRetained() {
    jdbc.update(
        "INSERT INTO lotto_record (public_id, owner_subject_id, round_no, numbers_key,"
            + " recommended_at) VALUES (UUID_TO_BIN(UUID()), ?, ?, ?, NOW(6))",
        member.subjectId(),
        ROUND,
        A.key());
    UUID record = register();
    String token =
        codec.issue(member.subjectPublicId(), CancelCommandService.OPERATION, null, null);
    UUID key = UUID.randomUUID();
    long version = ((Number) row(record).get("version")).longValue();

    assertThat(cancel(token, key, record, version).outcome())
        .isEqualTo(CancelCommandService.RETAINED);
    register();
    cancel(token, key, record, version);

    assertThat(row(record)).containsEntry("purchased", true).containsEntry("deleted_at", null);
  }

  @Test
  @DisplayName(
      "버전이 다르면 VERSION_CONFLICT로 확정 실패하고 다시 보내도 같은 실패, 같은 키에 다른 본문은 IDEMPOTENCY_CONFLICT, 다른 명령 토큰은 FORBIDDEN")
  void failures() {
    UUID record = register();
    String token =
        codec.issue(member.subjectPublicId(), CancelCommandService.OPERATION, null, null);
    UUID key = UUID.randomUUID();

    assertCode(() -> cancel(token, key, record, 7), ErrorCode.VERSION_CONFLICT);
    assertCode(() -> cancel(token, key, record, 7), ErrorCode.VERSION_CONFLICT);
    assertCode(() -> cancel(token, key, record, 0), ErrorCode.IDEMPOTENCY_CONFLICT);
    String other = codec.issue(member.subjectPublicId(), "RECOMMEND", ROUND, 1L);
    assertCode(() -> cancel(other, UUID.randomUUID(), record, 0), ErrorCode.FORBIDDEN);
    assertThat(row(record)).containsEntry("purchased", true);
  }

  @Test
  @DisplayName("명령 조회는 끝난 취소에 같은 완료 증명, 다른 주체는 NOT_FOUND, 확정 실패(지운 기록 다시 취소)는 그 오류, 처리 중은 진행 상태")
  void operationLookup() {
    UUID record = register();
    String token =
        codec.issue(member.subjectPublicId(), CancelCommandService.OPERATION, null, null);
    CancelCommandService.CancelProof proof = cancel(token, UUID.randomUUID(), record, 0);
    OperationQuery query = new OperationQuery(jdbc, List.of(commands));

    assertThat(query.find(member.subjectId(), proof.operationId()))
        .isEqualTo(new OperationQuery.Outcome.Done(proof.operationId(), proof));
    assertCode(
        () -> query.find(member.subjectId() + 1000, proof.operationId()), ErrorCode.NOT_FOUND);
    assertCode(() -> query.find(member.subjectId(), UUID.randomUUID()), ErrorCode.NOT_FOUND);

    UUID failedKey = UUID.randomUUID();
    assertCode(() -> cancel(token, failedKey, record, 9), ErrorCode.NOT_FOUND);
    UUID failed =
        DbValues.uuid(
            jdbc.queryForObject(
                "SELECT public_id FROM command_operation WHERE state = 'FAILED'", byte[].class));
    assertCode(() -> query.find(member.subjectId(), failed), ErrorCode.NOT_FOUND);

    jdbc.update("UPDATE command_operation SET state = 'PROCESSING' WHERE state = 'FAILED'");
    assertThat(query.find(member.subjectId(), failed))
        .isEqualTo(new OperationQuery.Outcome.Processing(failed));
  }

  private CancelCommandService.CancelProof cancel(
      String token, UUID key, UUID record, long version) {
    return commands.cancel(
        member.subjectId(), member.subjectPublicId(), token, key, record, version);
  }

  private UUID register() {
    return tx.execute(s -> purchases.register(member.subjectId(), ROUND, List.of(A)))
        .items()
        .get(0)
        .recordPublicId();
  }

  private Map<String, Object> row(UUID record) {
    return jdbc.queryForMap(
        "SELECT purchased, deleted_at, version FROM lotto_record WHERE public_id = ?",
        DbValues.bytes(record));
  }

  private static void assertCode(ThrowingCallable call, ErrorCode code) {
    assertThatThrownBy(call)
        .isInstanceOfSatisfying(
            BusinessException.class, e -> assertThat(e.getCode()).isEqualTo(code));
  }
}
