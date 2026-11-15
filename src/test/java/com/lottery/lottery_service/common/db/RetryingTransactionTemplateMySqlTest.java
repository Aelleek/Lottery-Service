package com.lottery.lottery_service.common.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.lottery.lottery_service.support.MySqlContainerSupport;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
