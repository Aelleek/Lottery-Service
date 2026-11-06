package com.lottery.lottery_service.round;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lottery.lottery_service.common.error.BusinessException;
import com.lottery.lottery_service.common.error.ErrorCode;
import com.lottery.lottery_service.round.domain.RoundSchedule;
import com.lottery.lottery_service.support.MySqlContainerSupport;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
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

/** {@link RoundStore}의 잠금과 DB 시각 판단을 MySQL에서 확인한다. */
class RoundStoreMySqlTest extends MySqlContainerSupport {

  private static final String DB = "round_store";

  private static DataSource dataSource;

  private JdbcTemplate jdbc;
  private TransactionTemplate tx;
  private RoundStore store;

  @BeforeAll
  static void migrate() throws Exception {
    dataSource = migratedDatabase(DB);
  }

  @BeforeEach
  void setUp() {
    jdbc = new JdbcTemplate(dataSource);
    jdbc.update("DELETE FROM lotto_round");
    DataSourceTransactionManager tm = new DataSourceTransactionManager(dataSource);
    tx = new TransactionTemplate(tm);
    store = new RoundStore(jdbc, tm);
  }

  @Test
  @DisplayName("등록한 일정이 그대로 달력에 실린다")
  void registerAndLoad() {
    RoundSchedule schedule =
        new RoundSchedule(
            1200,
            Instant.parse("2026-11-07T21:00:00Z"),
            Instant.parse("2026-11-14T11:00:00Z"),
            Instant.parse("2026-11-14T11:35:00Z"),
            1);
    store.register(schedule);

    assertThat(store.calendar().schedules()).containsEntry(1200, schedule);
  }

  @Test
  @DisplayName("판매 구간 안이면 잠금 뒤 DB 시각을 저장 시각으로 주고, 버전이 다르거나 마감이 지났으면 ROUND_CHANGED")
  void acceptRecommendation() {
    Instant now = store.dbNow();
    store.register(schedule(1200, now.minus(Duration.ofDays(1)), now.plusSeconds(3600)));
    store.register(schedule(1199, now.minus(Duration.ofDays(8)), now.minusSeconds(1)));

    Instant accepted = tx.execute(s -> store.acceptRecommendation(1200, 1));
    assertThat(accepted).isBetween(now, store.dbNow());

    assertRoundChanged(() -> tx.execute(s -> store.acceptRecommendation(1200, 2)));
    assertRoundChanged(() -> tx.execute(s -> store.acceptRecommendation(1199, 1)));
    assertRoundChanged(() -> tx.execute(s -> store.acceptRecommendation(1300, 1)));
  }

  @Test
  @DisplayName("마감 전에 잠금을 기다리기 시작해도 마감 뒤에 잠금을 잡으면 그 뒤 읽은 시각으로 거부한다(T25)")
  void lockWaitAcrossCloseIsRejected() throws Exception {
    Instant now = store.dbNow();
    store.register(schedule(1200, now.minus(Duration.ofDays(1)), now.plusMillis(1500)));

    try (Connection holder = dataSource.getConnection()) {
      holder.setAutoCommit(false);
      try (Statement st = holder.createStatement()) {
        st.executeQuery("SELECT round_no FROM lotto_round WHERE round_no = 1200 FOR UPDATE")
            .close();
      }
      CompletableFuture<Instant> waiting =
          CompletableFuture.supplyAsync(() -> tx.execute(s -> store.acceptRecommendation(1200, 1)));
      Thread.sleep(300);
      assertThat(waiting).isNotDone();
      Thread.sleep(1700);
      holder.commit();

      assertThatThrownBy(() -> waiting.get(10, TimeUnit.SECONDS))
          .hasCauseInstanceOf(BusinessException.class);
    }
  }

  @Test
  @DisplayName("봉인은 진행 중인 기록 저장(공유 잠금)이 끝나기를 기다리고, 마감 시각을 고정한다(T07)")
  void sealWaitsForInFlightWrite() throws Exception {
    Instant now = store.dbNow();
    Instant close = now.plusMillis(500).truncatedTo(ChronoUnit.MICROS);
    store.register(schedule(1200, now.minus(Duration.ofDays(1)), close));
    store.register(schedule(1201, close, close.plus(Duration.ofDays(7))));

    CountDownLatch locked = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    CompletableFuture<Instant> write =
        CompletableFuture.supplyAsync(
            () ->
                tx.execute(
                    s -> {
                      Instant at = store.acceptRecommendation(1200, 1);
                      locked.countDown();
                      await(release);
                      return at;
                    }));
    locked.await(5, TimeUnit.SECONDS);
    Thread.sleep(800);

    CompletableFuture<List<Integer>> seal = CompletableFuture.supplyAsync(store::sealDue);
    Thread.sleep(300);
    assertThat(seal).isNotDone();

    release.countDown();
    Instant acceptedAt = write.get(10, TimeUnit.SECONDS);
    assertThat(seal.get(10, TimeUnit.SECONDS)).containsExactly(1200);

    assertThat(acceptedAt).isBefore(close);
    assertThat(
            jdbc.queryForObject(
                "SELECT cutoff_close_at FROM lotto_round WHERE round_no = 1200",
                LocalDateTime.class))
        .isEqualTo(LocalDateTime.ofInstant(close, ZoneOffset.UTC));
    assertThat(
            jdbc.queryForObject(
                "SELECT cutoff_state FROM lotto_round WHERE round_no = 1201", String.class))
        .isEqualTo("OPEN");
    assertThat(store.sealDue()).isEmpty();
  }

  @Test
  @DisplayName("봉인 상태와 고정한 마감 시각을 공유 잠금 결과로 읽는다")
  void lockSharedShowsSeal() {
    Instant now = store.dbNow();
    store.register(schedule(1200, now.minus(Duration.ofDays(1)), now.minusSeconds(1)));
    store.sealDue();

    LockedRound locked = tx.execute(s -> store.lockShared(1200));

    assertThat(locked.sealed()).isTrue();
    assertThat(locked.cutoffCloseAt()).isEqualTo(locked.schedule().salesCloseAt());
    assertThat(locked.dbNow()).isAfter(locked.cutoffCloseAt());
  }

  @Test
  @DisplayName("서버 기본 시간대가 서울이어도 앱 연결의 NOW(6)는 UTC다(D-045)")
  void sessionTimeZoneIsUtc() throws Exception {
    try (Connection root = openConnection(null);
        Statement st = root.createStatement()) {
      st.execute("SET GLOBAL time_zone = '+09:00'");
      try (Connection app =
              DriverManager.getConnection(
                  databaseUrl(DB), MYSQL.getUsername(), MYSQL.getPassword());
          Statement q = app.createStatement();
          ResultSet rs = q.executeQuery("SELECT @@session.time_zone, UTC_TIMESTAMP(6), NOW(6)")) {
        rs.next();
        assertThat(rs.getString(1)).isIn("+00:00", "UTC");
        assertThat(rs.getObject(3, LocalDateTime.class))
            .isEqualTo(rs.getObject(2, LocalDateTime.class));
      } finally {
        st.execute("SET GLOBAL time_zone = 'SYSTEM'");
      }
    }
  }

  @Test
  @DisplayName("앱 연결의 격리 수준은 READ COMMITTED다(04 4절)")
  void isolationIsReadCommitted() {
    assertThat(jdbc.queryForObject("SELECT @@transaction_isolation", String.class))
        .isEqualTo("READ-COMMITTED");
  }

  private static RoundSchedule schedule(int roundNo, Instant open, Instant close) {
    return new RoundSchedule(roundNo, open, close, close.plus(Duration.ofMinutes(35)), 1);
  }

  private static void assertRoundChanged(Runnable call) {
    assertThatThrownBy(call::run)
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.getCode()).isEqualTo(ErrorCode.ROUND_CHANGED));
  }

  private static void await(CountDownLatch latch) {
    try {
      latch.await(10, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
