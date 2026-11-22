package com.lottery.lottery_service.common.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lottery.lottery_service.support.MySqlContainerSupport;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class RetryingTransactionTemplateMySqlTest extends MySqlContainerSupport {

  @Test
  @DisplayName("실제 교착에서 진 트랜잭션을 다시 실행해 두 트랜잭션이 모두 끝난다")
  void deadlockLoserIsRetried() throws Exception {
    recreateDatabase("retrying_tx");
    DataSource ds =
        new DriverManagerDataSource(
            databaseUrl("retrying_tx"), MYSQL.getUsername(), MYSQL.getPassword());
    JdbcTemplate jdbc = new JdbcTemplate(ds);
    jdbc.execute("CREATE TABLE t (id INT PRIMARY KEY, v INT NOT NULL)");
    jdbc.update("INSERT INTO t VALUES (1, 0), (2, 0)");
    RetryingTransactionTemplate tx =
        new RetryingTransactionTemplate(new DataSourceTransactionManager(ds));
    CyclicBarrier barrier = new CyclicBarrier(2);
    AtomicInteger attempts = new AtomicInteger();
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<?> a = pool.submit(() -> crossUpdate(tx, jdbc, barrier, attempts, 1, 2));
      Future<?> b = pool.submit(() -> crossUpdate(tx, jdbc, barrier, attempts, 2, 1));
      a.get(30, TimeUnit.SECONDS);
      b.get(30, TimeUnit.SECONDS);
    } finally {
      pool.shutdownNow();
    }

    assertThat(jdbc.queryForObject("SELECT SUM(v) FROM t", Integer.class)).isEqualTo(4);
    assertThat(attempts.get()).isGreaterThan(2);
  }

  @Test
  @DisplayName("실제 잠금 대기 시간 초과는 처음부터 다시 실행하고, 앞 시도의 쓰기는 남지 않는다")
  void lockWaitTimeoutIsRetriedFromScratch() throws Exception {
    JdbcTemplate jdbc = lockTable("retrying_tx_timeout");
    RetryingTransactionTemplate tx =
        new RetryingTransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
    AtomicInteger attempts = new AtomicInteger();
    try (Connection holder = holdRowLock("retrying_tx_timeout")) {
      tx.executeWithoutResult(
          s -> {
            if (attempts.incrementAndGet() == 2) {
              release(holder);
            }
            jdbc.execute("SET SESSION innodb_lock_wait_timeout = 1");
            jdbc.update("INSERT INTO log (attempt) VALUES (?)", attempts.get());
            jdbc.queryForObject("SELECT v FROM t WHERE id = 1 FOR UPDATE", Integer.class);
            jdbc.update("UPDATE t SET v = v + 1 WHERE id = 1");
          });
    }

    assertThat(attempts.get()).isEqualTo(2);
    assertThat(jdbc.queryForList("SELECT attempt FROM log", Integer.class)).containsExactly(2);
    assertThat(jdbc.queryForObject("SELECT v FROM t WHERE id = 1", Integer.class)).isEqualTo(1);
  }

  @Test
  @DisplayName("잠금 대기 시간 초과가 계속되면 두 번 다시 실행한 뒤 잠금 실패 예외를 던지고 쓰기는 남지 않는다")
  void lockWaitTimeoutGivesUpAfterTwoRetries() throws Exception {
    JdbcTemplate jdbc = lockTable("retrying_tx_timeout_all");
    RetryingTransactionTemplate tx =
        new RetryingTransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
    AtomicInteger attempts = new AtomicInteger();
    try (Connection holder = holdRowLock("retrying_tx_timeout_all")) {
      assertThatThrownBy(
              () ->
                  tx.executeWithoutResult(
                      s -> {
                        attempts.incrementAndGet();
                        jdbc.execute("SET SESSION innodb_lock_wait_timeout = 1");
                        jdbc.update("INSERT INTO log (attempt) VALUES (?)", attempts.get());
                        jdbc.queryForObject(
                            "SELECT v FROM t WHERE id = 1 FOR UPDATE", Integer.class);
                      }))
          .isInstanceOf(PessimisticLockingFailureException.class);
      holder.rollback();
    }

    assertThat(attempts.get()).isEqualTo(1 + RetryingTransactionTemplate.MAX_RETRIES);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM log", Integer.class)).isZero();
  }

  private static JdbcTemplate lockTable(String db) throws SQLException {
    recreateDatabase(db);
    JdbcTemplate jdbc =
        new JdbcTemplate(
            new DriverManagerDataSource(databaseUrl(db), MYSQL.getUsername(), MYSQL.getPassword()));
    jdbc.execute("CREATE TABLE t (id INT PRIMARY KEY, v INT NOT NULL)");
    jdbc.execute("CREATE TABLE log (id INT AUTO_INCREMENT PRIMARY KEY, attempt INT NOT NULL)");
    jdbc.update("INSERT INTO t VALUES (1, 0)");
    return jdbc;
  }

  private static Connection holdRowLock(String db) throws SQLException {
    Connection c = openConnection(db);
    c.setAutoCommit(false);
    try (Statement st = c.createStatement()) {
      st.executeQuery("SELECT v FROM t WHERE id = 1 FOR UPDATE").close();
    }
    return c;
  }

  private static void release(Connection holder) {
    try {
      holder.commit();
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  private static void crossUpdate(
      RetryingTransactionTemplate tx,
      JdbcTemplate jdbc,
      CyclicBarrier barrier,
      AtomicInteger attempts,
      int first,
      int second) {
    tx.executeWithoutResult(
        s -> {
          boolean firstTry = attempts.incrementAndGet() <= 2;
          jdbc.update("UPDATE t SET v = v + 1 WHERE id = ?", first);
          if (firstTry) {
            try {
              barrier.await(10, TimeUnit.SECONDS);
            } catch (Exception e) {
              throw new IllegalStateException(e);
            }
          }
          jdbc.update("UPDATE t SET v = v + 1 WHERE id = ?", second);
        });
  }
}
