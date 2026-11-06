package com.lottery.lottery_service.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lottery.lottery_service.common.number.LottoNumbers;
import com.lottery.lottery_service.support.MySqlContainerSupport;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * migration으로 만든 스키마에서 트랜잭션 rollback, version 비교 갱신, 유일키 잠금 대기가 설계대로 동작하는지 확인한다. 각 테스트는 커밋한 자료를
 * 남기므로 시작할 때 표를 비운다.
 */
class MigratedSchemaTransactionMySqlTest extends MySqlContainerSupport {

  private static final String DB = "migrated_tx";
  private static final int ROUND = 1200;
  private static final String KEY = LottoNumbers.of(1, 2, 3, 4, 5, 6).key();

  private long subject;

  @BeforeAll
  static void migrate() throws SQLException {
    recreateDatabase(DB);
    Flyway.configure()
        .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
        .schemas(DB)
        .createSchemas(false)
        .locations("classpath:db/migration")
        .load()
        .migrate();
  }

  @BeforeEach
  void reset() throws SQLException {
    try (Connection c = openConnection(DB);
        Statement st = c.createStatement()) {
      st.execute("DELETE FROM job");
      st.execute("DELETE FROM lotto_record");
      st.execute("DELETE FROM lotto_round");
      st.execute("DELETE FROM actor_subject");
      st.execute(
          "INSERT INTO lotto_round (round_no, sales_open_at, sales_close_at, draw_at,"
              + " schedule_version) VALUES ("
              + ROUND
              + ", '2026-11-07 12:00:00', '2026-11-14 02:00:00', '2026-11-14 11:35:00', 1)");
      subject = insertGuestSubject(c);
    }
  }

  @Test
  @DisplayName("같은 트랜잭션에서 기록과 job을 넣다가 실패하면 둘 다 남지 않는다")
  void failedTransactionRollsBackRecordAndJob() throws SQLException {
    try (Connection c = openConnection(DB)) {
      c.setAutoCommit(false);
      insertRecord(c, subject, KEY);
      insertJob(c, "EVALUATE_RECORD", "record-1");
      assertThatThrownBy(() -> insertRecord(c, subject, KEY))
          .isInstanceOf(SQLIntegrityConstraintViolationException.class)
          .hasMessageContaining("uq_record_combination");
      c.rollback();
    }

    assertThat(count("lotto_record")).isZero();
    assertThat(count("job")).isZero();
  }

  @Test
  @DisplayName("커밋한 기록과 job은 함께 남고, 같은 job 키는 두 번 등록되지 않는다")
  void committedTransactionKeepsRecordAndJob() throws SQLException {
    try (Connection c = openConnection(DB)) {
      c.setAutoCommit(false);
      insertRecord(c, subject, KEY);
      insertJob(c, "EVALUATE_RECORD", "record-1");
      c.commit();

      assertThatThrownBy(() -> insertJob(c, "EVALUATE_RECORD", "record-1"))
          .isInstanceOf(SQLIntegrityConstraintViolationException.class)
          .hasMessageContaining("uq_job_key");
      c.rollback();
    }

    assertThat(count("lotto_record")).isEqualTo(1);
    assertThat(count("job")).isEqualTo(1);
  }

  @Test
  @DisplayName("같은 version을 읽은 두 갱신 중 늦은 쪽은 0행이 바뀌고 앞 갱신을 덮어쓰지 않는다")
  void staleVersionUpdateChangesNothing() throws SQLException {
    long id;
    try (Connection c = openConnection(DB)) {
      id = insertRecord(c, subject, KEY);
    }
    long readVersion = 0;

    try (Connection first = openConnection(DB);
        Connection second = openConnection(DB)) {
      assertThat(markPurchased(first, id, readVersion, true)).isEqualTo(1);
      assertThat(markPurchased(second, id, readVersion, false)).isZero();
    }

    try (Connection c = openConnection(DB);
        Statement st = c.createStatement();
        ResultSet rs =
            st.executeQuery("SELECT purchased, version FROM lotto_record WHERE id = " + id)) {
      rs.next();
      assertThat(rs.getBoolean(1)).isTrue();
      assertThat(rs.getLong(2)).isEqualTo(1);
    }
  }

  @Test
  @DisplayName("커밋 전인 같은 조합 삽입을 다른 요청은 기다리고, 앞 요청이 rollback하면 성공한다")
  void concurrentDuplicateWaitsAndSucceedsAfterRollback() throws Exception {
    try (Connection first = openConnection(DB)) {
      first.setAutoCommit(false);
      insertRecord(first, subject, KEY);

      CompletableFuture<Long> second =
          CompletableFuture.supplyAsync(
              () -> {
                try (Connection c = openConnection(DB)) {
                  return insertRecord(c, subject, KEY);
                } catch (SQLException e) {
                  throw new IllegalStateException(e);
                }
              });
      Thread.sleep(500);
      assertThat(second).isNotDone();

      first.rollback();
      assertThat(second.get(10, TimeUnit.SECONDS)).isPositive();
    }
    assertThat(count("lotto_record")).isEqualTo(1);
  }

  @Test
  @DisplayName("커밋 전인 같은 조합 삽입을 다른 요청은 기다리고, 앞 요청이 커밋하면 중복으로 거부된다")
  void concurrentDuplicateRejectedAfterCommit() throws Exception {
    try (Connection first = openConnection(DB)) {
      first.setAutoCommit(false);
      insertRecord(first, subject, KEY);

      CompletableFuture<Long> second =
          CompletableFuture.supplyAsync(
              () -> {
                try (Connection c = openConnection(DB)) {
                  return insertRecord(c, subject, KEY);
                } catch (SQLException e) {
                  throw new IllegalStateException(e);
                }
              });
      Thread.sleep(500);
      assertThat(second).isNotDone();

      first.commit();
      assertThatThrownBy(() -> second.get(10, TimeUnit.SECONDS))
          .hasRootCauseInstanceOf(SQLIntegrityConstraintViolationException.class);
    }
    assertThat(count("lotto_record")).isEqualTo(1);
  }

  private static long insertGuestSubject(Connection c) throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO actor_subject (public_id, kind, state) VALUES (UUID_TO_BIN(?), 'GUEST',"
                + " 'ACTIVE')",
            Statement.RETURN_GENERATED_KEYS)) {
      ps.setString(1, UUID.randomUUID().toString());
      ps.executeUpdate();
      return generatedKey(ps);
    }
  }

  private static long insertRecord(Connection c, long owner, String key) throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO lotto_record (public_id, owner_subject_id, round_no, numbers_key)"
                + " VALUES (UUID_TO_BIN(UUID()), ?, ?, ?)",
            Statement.RETURN_GENERATED_KEYS)) {
      ps.setLong(1, owner);
      ps.setInt(2, ROUND);
      ps.setString(3, key);
      ps.executeUpdate();
      return generatedKey(ps);
    }
  }

  private static void insertJob(Connection c, String type, String dedupeKey) throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO job (public_id, job_type, dedupe_key, state, payload, available_at)"
                + " VALUES (UUID_TO_BIN(UUID()), ?, ?, 'READY', JSON_OBJECT(), NOW(6))")) {
      ps.setString(1, type);
      ps.setBytes(2, dedupeKey.getBytes(StandardCharsets.US_ASCII));
      ps.executeUpdate();
    }
  }

  private static int markPurchased(Connection c, long id, long expectedVersion, boolean purchased)
      throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "UPDATE lotto_record SET purchased = ?, purchased_at = IF(?, NOW(6), NULL),"
                + " version = version + 1 WHERE id = ? AND version = ?")) {
      ps.setBoolean(1, purchased);
      ps.setBoolean(2, purchased);
      ps.setLong(3, id);
      ps.setLong(4, expectedVersion);
      return ps.executeUpdate();
    }
  }

  private static long count(String table) throws SQLException {
    try (Connection c = openConnection(DB);
        Statement st = c.createStatement();
        ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + table)) {
      rs.next();
      return rs.getLong(1);
    }
  }

  private static long generatedKey(Statement st) throws SQLException {
    try (ResultSet rs = st.getGeneratedKeys()) {
      rs.next();
      return rs.getLong(1);
    }
  }
}
