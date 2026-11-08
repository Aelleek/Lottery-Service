package com.lottery.lottery_service.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lottery.lottery_service.support.MySqlContainerSupport;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 제품 migration({@code db/migration})이 빈 MySQL 8.4에 적용되고 설계 스키마와 같은지 확인한다. */
class FlywayMigrationMySqlTest extends MySqlContainerSupport {

  private static final Path SCHEMA = Path.of("docs/contracts/schema.sql");
  private static final Path V1 =
      Path.of("src/main/resources/db/migration/V1__first_release_schema.sql");

  @Test
  @DisplayName("V1은 설계 스키마 파일과 내용이 같다")
  void v1MatchesDesignSchema() throws Exception {
    assertThat(Files.readString(V1)).isEqualTo(Files.readString(SCHEMA));
  }

  @Test
  @DisplayName("빈 DB에 migration을 적용하면 설계 스키마의 테이블이 모두 생기고, 다시 적용해도 바뀌지 않는다")
  void migratesEmptyDatabase() throws Exception {
    String db = "flyway_empty";
    recreateDatabase(db);
    Flyway flyway = flyway(db);

    MigrateResult first = flyway.migrate();
    assertThat(first.success).isTrue();
    assertThat(first.migrationsExecuted).isEqualTo(1);
    assertThat(first.targetSchemaVersion).isEqualTo("1");

    long expectedTables = countCreateTables(Files.readString(SCHEMA));
    assertThat(countTables(db, "flyway_schema_history"))
        .isEqualTo(expectedTables)
        .isEqualTo(26 + 2);

    MigrateResult second = flyway.migrate();
    assertThat(second.migrationsExecuted).isZero();
    flyway.validate();
  }

  @Test
  @DisplayName("옛 테이블이 남은 DB에는 migration을 적용하지 않는다")
  void refusesNonEmptyDatabaseWithoutHistory() throws Exception {
    String db = "flyway_leftover";
    recreateDatabase(db);
    try (Connection c = openConnection(db);
        Statement st = c.createStatement()) {
      st.execute("CREATE TABLE lotto_record (id BIGINT PRIMARY KEY, numbers VARCHAR(64))");
    }

    assertThatThrownBy(() -> flyway(db).migrate())
        .isInstanceOf(FlywayException.class)
        .hasMessageContaining("non-empty schema");
    assertThat(countTables(db, "")).isEqualTo(1);
  }

  private static Flyway flyway(String db) {
    return Flyway.configure()
        .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
        .schemas(db)
        .createSchemas(false)
        .locations("classpath:db/migration")
        .load();
  }

  private static long countTables(String db, String excluded) throws Exception {
    try (Connection c = openConnection(null);
        PreparedStatement ps =
            c.prepareStatement(
                "SELECT COUNT(*) FROM information_schema.tables"
                    + " WHERE table_schema = ? AND table_name <> ?")) {
      ps.setString(1, db);
      ps.setString(2, excluded);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }

  private static long countCreateTables(String sql) {
    return sql.lines().filter(l -> l.startsWith("CREATE TABLE ")).count();
  }
}
