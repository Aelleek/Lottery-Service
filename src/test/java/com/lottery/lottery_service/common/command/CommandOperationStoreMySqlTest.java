package com.lottery.lottery_service.common.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lottery.lottery_service.account.GuestCredential;
import com.lottery.lottery_service.account.GuestCredentialStore;
import com.lottery.lottery_service.common.error.BusinessException;
import com.lottery.lottery_service.common.error.ErrorCode;
import com.lottery.lottery_service.support.MutableClock;
import com.lottery.lottery_service.support.MySqlContainerSupport;
import com.lottery.lottery_service.support.TestSecrets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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

/** {@link CommandOperationStore}가 같은 요청을 한 번만 처리하게 하는지 MySQL에서 확인한다. */
class CommandOperationStoreMySqlTest extends MySqlContainerSupport {

  private static final Instant T0 = Instant.parse("2026-11-08T01:00:00Z");

  private static DataSource dataSource;

  private final MutableClock clock = new MutableClock(T0);
  private JdbcTemplate jdbc;
  private TransactionTemplate tx;
  private CommandOperationStore store;
  private CommandTokenCodec codec;
  private GuestCredential guest;
  private CommandToken token;
  private final UUID key = UUID.randomUUID();
  private final byte[] hash = new byte[32];

  @BeforeAll
  static void migrate() throws Exception {
    dataSource = migratedDatabase("command_operation");
  }

  @BeforeEach
  void setUp() {
    jdbc = new JdbcTemplate(dataSource);
    jdbc.update("DELETE FROM command_operation");
    jdbc.update("DELETE FROM guest_credential");
    jdbc.update("DELETE FROM actor_subject");
    DataSourceTransactionManager tm = new DataSourceTransactionManager(dataSource);
    tx = new TransactionTemplate(tm);
    store = new CommandOperationStore(jdbc, tm, clock);
    codec = new CommandTokenCodec(TestSecrets.keys(), clock);
    guest = new GuestCredentialStore(jdbc, tm, clock, TestSecrets.keys()).issue();
    token =
        codec.verify(
            codec.issue(guest.subjectPublicId(), "PURCHASE", null, null),
            guest.subjectPublicId(),
            "PURCHASE");
  }

  @Test
  @DisplayName("응답 요약과 함께 끝내면 요약을 다시 읽을 수 있고, 처리 권한을 넘겨받은 뒤의 옛 처리는 요약을 남기지 못한다")
  void summaryAndLeaseLoss() {
    CommandStart.Started first = started(store.begin(guest.subjectId(), token, key, hash));
    clock.set(T0.plus(CommandOperationStore.LEASE));
    CommandStart.Started second = started(store.begin(guest.subjectId(), token, key, hash));

    assertThatThrownBy(
            () ->
                tx.executeWithoutResult(
                    s -> store.succeedWithSummary(first, "PURCHASE_REGISTERED", "{\"a\":1}")))
        .isInstanceOf(IllegalStateException.class);
    assertThat(store.summary(second.publicId())).isEmpty();
    tx.executeWithoutResult(
        s -> store.succeedWithSummary(second, "PURCHASE_REGISTERED", "{\"b\":2}"));
    assertThat(store.summary(second.publicId()))
        .hasValueSatisfying(v -> assertThat(v).contains("\"b\""));
    assertThat(store.summary(UUID.randomUUID())).isEmpty();
  }

  @Test
  @DisplayName("다른 주체가 같은 토큰 ID·키를 보내도 앞 주체의 기록을 돌려주지 않고 자기 기록을 새로 만든다")
  void scopedToSubject() {
    CommandStart.Started first = started(store.begin(guest.subjectId(), token, key, hash));
    store.succeed(first, UUID.randomUUID(), "CREATED");
    long other =
        new GuestCredentialStore(
                jdbc, new DataSourceTransactionManager(dataSource), clock, TestSecrets.keys())
            .issue()
            .subjectId();

    CommandStart start = store.begin(other, token, key, hash);

    assertThat(start).isInstanceOf(CommandStart.Started.class);
    assertThat(((CommandStart.Started) start).publicId()).isNotEqualTo(first.publicId());
  }

  @Test
  @DisplayName("처음 요청은 처리를 맡고, 처리 중 같은 요청은 PROCESSING, 끝난 뒤에는 처음 결과를 돌려준다(T04)")
  void sameRequestHandledOnce() {
    CommandStart.Started started = started(store.begin(guest.subjectId(), token, key, hash));
    assertThat(store.begin(guest.subjectId(), token, key, hash))
        .isInstanceOf(CommandStart.InProgress.class);

    UUID resource = UUID.randomUUID();
    tx.executeWithoutResult(s -> store.succeed(started, resource, "CREATED"));

    assertThat(store.begin(guest.subjectId(), token, key, hash))
        .isEqualTo(new CommandStart.Completed(started.publicId(), resource, "CREATED"));
  }

  @Test
  @DisplayName("같은 키에 다른 본문이면 IDEMPOTENCY_CONFLICT")
  void differentBodyConflicts() {
    store.begin(guest.subjectId(), token, key, hash);
    byte[] other = new byte[32];
    other[0] = 1;

    assertCode(
        () -> store.begin(guest.subjectId(), token, key, other), ErrorCode.IDEMPOTENCY_CONFLICT);
  }

  @Test
  @DisplayName("만료된 토큰의 새 요청은 COMMAND_EXPIRED, 끝난 요청의 재전송은 처음 결과를 준다(T05 일부)")
  void expiredToken() {
    CommandStart.Started started = started(store.begin(guest.subjectId(), token, key, hash));
    UUID resource = UUID.randomUUID();
    tx.executeWithoutResult(s -> store.succeed(started, resource, "CREATED"));
    clock.set(token.expiresAt());

    assertThat(store.begin(guest.subjectId(), token, key, hash))
        .isInstanceOf(CommandStart.Completed.class);
    assertCode(
        () -> store.begin(guest.subjectId(), token, UUID.randomUUID(), hash),
        ErrorCode.COMMAND_EXPIRED);
  }

  @Test
  @DisplayName("업무 저장이 rollback되면 성공 표시도 남지 않고, 처리 권한이 지나면 재시도가 넘겨받으며 옛 처리는 끝낼 수 없다")
  void rollbackAndTakeover() {
    CommandStart.Started first = started(store.begin(guest.subjectId(), token, key, hash));
    tx.executeWithoutResult(
        s -> {
          store.succeed(first, UUID.randomUUID(), "CREATED");
          s.setRollbackOnly();
        });
    assertThat(store.begin(guest.subjectId(), token, key, hash))
        .isInstanceOf(CommandStart.InProgress.class);

    clock.set(T0.plus(CommandOperationStore.LEASE));
    CommandStart.Started second = started(store.begin(guest.subjectId(), token, key, hash));

    assertThat(second.operationId()).isEqualTo(first.operationId());
    assertThatThrownBy(
            () -> tx.executeWithoutResult(s -> store.succeed(first, UUID.randomUUID(), "CREATED")))
        .isInstanceOf(IllegalStateException.class);
    tx.executeWithoutResult(s -> store.succeed(second, UUID.randomUUID(), "CREATED"));
  }

  @Test
  @DisplayName("다시 시도할 실패는 재전송이 처리를 다시 맡고, 확정 실패는 같은 실패를 돌려준다")
  void retryableAndFailed() {
    CommandStart.Started first = started(store.begin(guest.subjectId(), token, key, hash));
    store.markRetryable(first, "TEMPORARILY_UNAVAILABLE");
    CommandStart.Started again = started(store.begin(guest.subjectId(), token, key, hash));
    store.markFailed(again, "ROUND_CHANGED");

    assertThat(store.begin(guest.subjectId(), token, key, hash))
        .isEqualTo(new CommandStart.Failed(first.publicId(), "ROUND_CHANGED"));
  }

  @Test
  @DisplayName("같은 요청이 동시에 와도 처리를 맡는 것은 하나다")
  void concurrentBegin() throws Exception {
    CountDownLatch start = new CountDownLatch(1);
    List<CompletableFuture<CommandStart>> calls = new ArrayList<>();
    for (int i = 0; i < 4; i++) {
      calls.add(
          CompletableFuture.supplyAsync(
              () -> {
                try {
                  start.await();
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                }
                return store.begin(guest.subjectId(), token, key, hash);
              }));
    }
    start.countDown();

    long startedCount = 0;
    for (CompletableFuture<CommandStart> c : calls) {
      CommandStart result = c.get(20, TimeUnit.SECONDS);
      startedCount += result instanceof CommandStart.Started ? 1 : 0;
      assertThat(result).isInstanceOfAny(CommandStart.Started.class, CommandStart.InProgress.class);
    }
    assertThat(startedCount).isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM command_operation", Long.class))
        .isEqualTo(1);
  }

  private static CommandStart.Started started(CommandStart start) {
    assertThat(start).isInstanceOf(CommandStart.Started.class);
    return (CommandStart.Started) start;
  }

  private static void assertCode(Runnable call, ErrorCode code) {
    assertThatThrownBy(call::run)
        .isInstanceOfSatisfying(
            BusinessException.class, e -> assertThat(e.getCode()).isEqualTo(code));
  }
}
