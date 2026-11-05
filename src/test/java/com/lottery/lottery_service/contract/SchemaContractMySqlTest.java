package com.lottery.lottery_service.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lottery.lottery_service.common.number.LottoNumbers;
import com.lottery.lottery_service.support.MySqlContainerSupport;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Statement;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/**
 * 설계 스키마({@code docs/contracts/schema.sql})를 빈 MySQL 8.4에 적용하고 핵심 제약이 실제로 막는지 확인한다. 각 테스트는 트랜잭션 안에서
 * 돌고 끝나면 되돌린다.
 */
class SchemaContractMySqlTest extends MySqlContainerSupport {

  private static final Path SCHEMA = Path.of("docs/contracts/schema.sql");
  private static final String DB = "schema_contract";

  private Connection conn;

  @BeforeAll
  static void applySchema() throws Exception {
    recreateDatabase(DB);
    try (Connection c = openConnection(DB)) {
      ScriptUtils.executeSqlScript(c, new FileSystemResource(SCHEMA));
    }
  }

  @BeforeEach
  void begin() throws SQLException {
    conn = openConnection(DB);
    conn.setAutoCommit(false);
  }

  @AfterEach
  void rollback() throws SQLException {
    conn.rollback();
    conn.close();
  }

  @Test
  @DisplayName("빈 DB에 설계 스키마의 모든 테이블이 만들어진다")
  void allTablesCreated() throws Exception {
    long expected = countCreateTables(Files.readString(SCHEMA));
    try (PreparedStatement ps =
        conn.prepareStatement(
            "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = ?")) {
      ps.setString(1, DB);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        assertThat(rs.getLong(1)).isEqualTo(expected).isPositive();
      }
    }
  }

  @Test
  @DisplayName("같은 주체·회차에 순서만 다른 같은 조합은 두 번째 행이 거부된다")
  void sameCombinationRejected() throws SQLException {
    long subject = insertGuestSubject();
    insertRound(1200);
    insertRecord(subject, 1200, LottoNumbers.of(1, 2, 3, 4, 5, 6).key());

    assertThatThrownBy(() -> insertRecord(subject, 1200, LottoNumbers.of(6, 5, 4, 3, 2, 1).key()))
        .isInstanceOf(SQLIntegrityConstraintViolationException.class)
        .hasMessageContaining("uq_record_combination");
  }

  @Test
  @DisplayName("다른 주체, 다른 회차, 다른 조합은 함께 저장된다(T08)")
  void differentOwnerRoundOrNumbersAllowed() throws SQLException {
    long a = insertGuestSubject();
    long b = insertGuestSubject();
    insertRound(1200);
    insertRound(1201);
    String key = LottoNumbers.of(1, 2, 3, 4, 5, 6).key();

    insertRecord(a, 1200, key);
    insertRecord(b, 1200, key);
    insertRecord(a, 1201, key);
    insertRecord(a, 1200, LottoNumbers.of(1, 2, 3, 4, 5, 7).key());

    assertThat(countRecords()).isEqualTo(4);
  }

  @Test
  @DisplayName("없는 주체나 회차를 가리키는 기록은 FK가 거부한다")
  void foreignKeysRejectMissingParents() throws SQLException {
    long subject = insertGuestSubject();
    insertRound(1200);
    String key = LottoNumbers.of(1, 2, 3, 4, 5, 6).key();

    assertThatThrownBy(() -> insertRecord(subject + 1000, 1200, key))
        .isInstanceOf(SQLIntegrityConstraintViolationException.class)
        .hasMessageContaining("fk_lotto_record_0");
    assertThatThrownBy(() -> insertRecord(subject, 9999, key))
        .isInstanceOf(SQLIntegrityConstraintViolationException.class)
        .hasMessageContaining("fk_lotto_record_1");
  }

  @Test
  @DisplayName("기록이 있는 주체는 지울 수 없다(ON DELETE CASCADE 없음)")
  void parentDeleteRestricted() throws SQLException {
    long subject = insertGuestSubject();
    insertRound(1200);
    insertRecord(subject, 1200, LottoNumbers.of(1, 2, 3, 4, 5, 6).key());

    assertThatThrownBy(() -> execute("DELETE FROM actor_subject WHERE id = " + subject))
        .isInstanceOf(SQLIntegrityConstraintViolationException.class);
  }

  @Test
  @DisplayName("구매 여부와 구매 시각이 어긋나면 CHECK가 거부한다")
  void purchaseCheck() throws SQLException {
    long subject = insertGuestSubject();
    insertRound(1200);

    assertThatThrownBy(
            () ->
                execute(
                    "INSERT INTO lotto_record (public_id, owner_subject_id, round_no, numbers_key,"
                        + " purchased, purchased_at, created_at) VALUES (UUID_TO_BIN(UUID()), "
                        + subject
                        + ", 1200, '01 02 03 04 05 06', TRUE, NULL, NOW(6))"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("ck_lotto_record_1");
  }

  @Test
  @DisplayName("비회원 주체에 회원 ID를 붙이면 CHECK가 거부한다")
  void subjectKindCheck() {
    assertThatThrownBy(
            () ->
                execute(
                    "INSERT INTO actor_subject (public_id, member_id, kind, state)"
                        + " VALUES (UUID_TO_BIN(UUID()), NULL, 'MEMBER', 'ACTIVE')"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("ck_actor_subject_2");
  }

  @Test
  @DisplayName("새 기록의 version은 0에서 시작한다")
  void versionStartsAtZero() throws SQLException {
    long subject = insertGuestSubject();
    insertRound(1200);
    insertRecord(subject, 1200, LottoNumbers.of(1, 2, 3, 4, 5, 6).key());

    try (Statement st = conn.createStatement();
        ResultSet rs = st.executeQuery("SELECT version FROM lotto_record")) {
      rs.next();
      assertThat(rs.getLong(1)).isZero();
    }
  }

  @Test
  @DisplayName("번호 키는 대소문자·공백 비교가 아니라 바이트로 비교한다(ascii_bin)")
  void numbersKeyIsBinaryCollated() throws SQLException {
    try (PreparedStatement ps =
        conn.prepareStatement(
            "SELECT character_set_name, collation_name FROM information_schema.columns"
                + " WHERE table_schema = ? AND table_name = 'lotto_record'"
                + " AND column_name = 'numbers_key'")) {
      ps.setString(1, DB);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        assertThat(rs.getString(1)).isEqualTo("ascii");
        assertThat(rs.getString(2)).isEqualTo("ascii_bin");
      }
    }
  }

  private long insertGuestSubject() throws SQLException {
    try (PreparedStatement ps =
        conn.prepareStatement(
            "INSERT INTO actor_subject (public_id, kind, state) VALUES (UUID_TO_BIN(?), 'GUEST',"
                + " 'ACTIVE')",
            Statement.RETURN_GENERATED_KEYS)) {
      ps.setString(1, UUID.randomUUID().toString());
      ps.executeUpdate();
      try (ResultSet rs = ps.getGeneratedKeys()) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }

  private void insertRound(int round) throws SQLException {
    execute(
        "INSERT INTO lotto_round (round_no, sales_open_at, sales_close_at, draw_at,"
            + " schedule_version) VALUES ("
            + round
            + ", '2026-11-07 21:00:00', '2026-11-14 11:00:00', '2026-11-14 11:35:00', 1)");
  }

  private void insertRecord(long subject, int round, String key) throws SQLException {
    try (PreparedStatement ps =
        conn.prepareStatement(
            "INSERT INTO lotto_record (public_id, owner_subject_id, round_no, numbers_key,"
                + " created_at) VALUES (UUID_TO_BIN(UUID()), ?, ?, ?, NOW(6))")) {
      ps.setLong(1, subject);
      ps.setInt(2, round);
      ps.setString(3, key);
      ps.executeUpdate();
    }
  }

  private long countRecords() throws SQLException {
    try (Statement st = conn.createStatement();
        ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM lotto_record")) {
      rs.next();
      return rs.getLong(1);
    }
  }

  private void execute(String sql) throws SQLException {
    try (Statement st = conn.createStatement()) {
      st.execute(sql);
    }
  }

  private static long countCreateTables(String ddl) throws IOException {
    Matcher m = Pattern.compile("(?m)^CREATE TABLE ").matcher(ddl);
    long n = 0;
    while (m.find()) {
      n++;
    }
    return n;
  }
}
