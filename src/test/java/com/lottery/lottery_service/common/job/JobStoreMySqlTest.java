package com.lottery.lottery_service.common.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lottery.lottery_service.support.MySqlContainerSupport;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** {@link JobStore}의 등록, 가져가기, lease 검사, 회수를 migration으로 만든 MySQL 스키마에서 확인한다. */
class JobStoreMySqlTest extends MySqlContainerSupport {

  private static final String DB = "job_store";
  private static final Instant T0 = Instant.parse("2026-11-14T12:00:00Z");
  private static final Duration LEASE = Duration.ofMinutes(5);
  private static final List<String> TYPES = List.of("EVALUATE");

  private static DataSource dataSource;

  private final MutableClock clock = new MutableClock(T0);
  private JdbcTemplate jdbc;
  private TransactionTemplate tx;
  private JobStore store;

  @BeforeAll
  static void migrate() throws SQLException {
    dataSource = migratedDatabase(DB);
  }

  @BeforeEach
  void setUp() {
    jdbc = new JdbcTemplate(dataSource);
    jdbc.update("DELETE FROM job");
    DataSourceTransactionManager tm = new DataSourceTransactionManager(dataSource);
    tx = new TransactionTemplate(tm);
    store = new JobStore(jdbc, tm, clock, new JobProperties(5, LEASE));
  }

  @Test
  @DisplayName("같은 종류·중복 키는 한 번만 등록된다")
  void enqueueIsDeduplicated() {
    assertThat(store.enqueue("EVALUATE", "round:1200", "{}", T0)).isTrue();
    assertThat(store.enqueue("EVALUATE", "round:1200", "{\"x\":1}", T0)).isFalse();
    assertThat(store.enqueue("EVALUATE", "round:1201", "{}", T0)).isTrue();

    assertThat(count("job_type = 'EVALUATE'")).isEqualTo(2);
  }

  @Test
  @DisplayName("업무 트랜잭션이 rollback되면 그 안에서 등록한 job도 남지 않는다")
  void enqueueJoinsCallerTransaction() {
    tx.executeWithoutResult(
        status -> {
          store.enqueue("EVALUATE", "round:1200", "{}", T0);
          status.setRollbackOnly();
        });

    assertThat(count("1 = 1")).isZero();
  }

  @Test
  @DisplayName("때가 된 job만 가져가고, 가져가면 RUNNING과 lease가 생긴다")
  void claimOnlyDueJobs() {
    store.enqueue("EVALUATE", "later", "{}", T0.plusSeconds(60));
    store.enqueue("OTHER", "other", "{}", T0);
    assertThat(store.claim(TYPES)).isEmpty();

    store.enqueue("EVALUATE", "now", "{\"round\":1200}", T0);
    JobLease lease = store.claim(TYPES).orElseThrow();

    assertThat(lease.payload()).contains("1200");
    assertThat(lease.leaseUntil()).isEqualTo(T0.plus(LEASE));
    assertThat(lease.attemptCount()).isZero();
    assertThat(row(lease.id())).containsEntry("state", "RUNNING");
    assertThat(store.claim(TYPES)).isEmpty();
  }

  @Test
  @DisplayName("다른 트랜잭션이 잠근 job은 건너뛰고 다음 job을 가져간다(SKIP LOCKED)")
  void claimSkipsLockedRows() throws Exception {
    store.enqueue("EVALUATE", "a", "{}", T0);
    store.enqueue("EVALUATE", "b", "{}", T0.plusMillis(1));
    long first = jdbc.queryForObject("SELECT id FROM job WHERE dedupe_key = 'a'", Long.class);
    clock.set(T0.plusSeconds(1));

    try (Connection c = dataSource.getConnection()) {
      c.setAutoCommit(false);
      try (Statement st = c.createStatement()) {
        st.executeQuery("SELECT id FROM job WHERE id = " + first + " FOR UPDATE").close();
      }
      JobLease lease = store.claim(TYPES).orElseThrow();
      assertThat(lease.id()).isNotEqualTo(first);
      c.rollback();
    }
  }

  @Test
  @DisplayName("lease 만료 뒤 회수되고 새 worker가 끝낸 job에 옛 worker가 저장하면 거부되고 상태는 그대로다(T24)")
  void staleWorkerIsRejected() {
    store.enqueue("EVALUATE", "round:1200", "{}", T0);
    JobLease old = store.claim(TYPES).orElseThrow();
    store.saveProgress(old, "{\"lastId\":10}");

    clock.set(T0.plus(LEASE));
    assertThat(store.recoverExpired())
        .containsExactly(new RecoveredJob(old.id(), "EVALUATE", JobState.RETRY_WAIT, 1));

    JobLease fresh = store.claim(TYPES).orElseThrow();
    assertThat(fresh.cursor()).contains("10");
    assertThat(fresh.attemptCount()).isEqualTo(1);
    store.saveProgress(fresh, "{\"lastId\":50}");
    store.complete(fresh);

    AtomicReference<Throwable> error = new AtomicReference<>();
    try {
      tx.executeWithoutResult(
          status -> {
            store.enqueue("EVALUATE", "side-effect", "{}", T0);
            store.saveProgress(old, "{\"lastId\":20}");
          });
    } catch (LeaseLostException e) {
      error.set(e);
    }

    assertThat(error.get()).isInstanceOf(LeaseLostException.class);
    assertThatThrownBy(() -> store.complete(old)).isInstanceOf(LeaseLostException.class);
    assertThat(row(old.id())).containsEntry("state", "SUCCEEDED");
    assertThat((String) row(old.id()).get("cursor_json")).contains("50");
    assertThat(count("dedupe_key = 'side-effect'")).isZero();
  }

  @Test
  @DisplayName("lease 만료 전에는 회수하지 않고, 만료 뒤에는 lease를 쥔 worker도 저장할 수 없다")
  void leaseBoundary() {
    store.enqueue("EVALUATE", "round:1200", "{}", T0);
    JobLease lease = store.claim(TYPES).orElseThrow();

    clock.set(T0.plus(LEASE).minusNanos(1000));
    assertThat(store.recoverExpired()).isEmpty();
    store.saveProgress(lease, "{}");

    clock.set(T0.plus(LEASE));
    assertThatThrownBy(() -> store.saveProgress(lease, "{}"))
        .isInstanceOf(LeaseLostException.class);
  }

  @Test
  @DisplayName("회수가 시도 상한에 닿으면 DEAD가 되고 운영자 알림 job이 한 건만 생긴다(T45)")
  void recoveryMakesDeadAfterMaxAttempts() {
    store.enqueue("EVALUATE", "round:1200", "{}", T0);
    Instant now = T0;
    RecoveredJob last = null;
    for (int i = 1; i <= 5; i++) {
      clock.set(now);
      store.claim(TYPES).orElseThrow();
      now = now.plus(LEASE);
      clock.set(now);
      List<RecoveredJob> recovered = store.recoverExpired();
      assertThat(recovered).hasSize(1);
      last = recovered.get(0);
      assertThat(last.attemptCount()).isEqualTo(i);
      assertThat(last.state()).isEqualTo(i < 5 ? JobState.RETRY_WAIT : JobState.DEAD);
    }

    assertThat(row(last.id()))
        .containsEntry("state", "DEAD")
        .containsEntry("last_error_code", "LEASE_EXPIRED");
    assertThat(store.claim(TYPES)).isEmpty();
    assertThat(store.recoverExpired()).isEmpty();
    assertThat(count("job_type = 'OPERATOR_ALERT' AND state = 'READY'")).isEqualTo(1);
  }

  @Test
  @DisplayName("처리 실패는 다음 시도 시각까지 RETRY_WAIT로 두고, 상한에 닿으면 DEAD와 운영자 알림")
  void failRetriesThenDies() {
    store.enqueue("EVALUATE", "round:1200", "{}", T0);
    Instant now = T0;
    for (int i = 1; i <= 5; i++) {
      clock.set(now);
      JobLease lease = store.claim(TYPES).orElseThrow();
      JobState state = store.fail(lease, "SOURCE_DOWN", now.plusSeconds(30));
      assertThat(state).isEqualTo(i < 5 ? JobState.RETRY_WAIT : JobState.DEAD);
      if (i < 5) {
        clock.set(now.plusSeconds(29));
        assertThat(store.claim(TYPES)).isEmpty();
      }
      now = now.plusSeconds(30);
    }

    assertThat(count("job_type = 'EVALUATE' AND state = 'DEAD' AND attempt_count = 5"))
        .isEqualTo(1);
    assertThat(count("job_type = 'OPERATOR_ALERT'")).isEqualTo(1);
  }

  private long count(String where) {
    return jdbc.queryForObject("SELECT COUNT(*) FROM job WHERE " + where, Long.class);
  }

  private Map<String, Object> row(long id) {
    return jdbc.queryForMap("SELECT state, cursor_json, last_error_code FROM job WHERE id = ?", id);
  }
}
