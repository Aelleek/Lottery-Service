package com.lottery.lottery_service;

import static org.assertj.core.api.Assertions.assertThat;

import com.lottery.lottery_service.support.MySqlContainerSupport;
import java.sql.SQLException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 빈 MySQL에서 앱이 시작하면서 Flyway V1을 적용하고 새 스키마로 뜨는지 확인한다. */
@SpringBootTest
class AppStartupMySqlTest extends MySqlContainerSupport {

  private static final String DB = "app_startup";

  @Autowired private JdbcTemplate jdbc;

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) throws SQLException {
    recreateDatabase(DB);
    registry.add("spring.datasource.url", () -> databaseUrl(DB));
    registry.add("spring.datasource.username", MYSQL::getUsername);
    registry.add("spring.datasource.password", MYSQL::getPassword);
    registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
    registry.add("spring.flyway.enabled", () -> "true");
  }

  @Test
  @DisplayName("앱 시작 때 V1이 적용되고 옛 테이블은 만들어지지 않는다")
  void flywayAppliedOnStartup() {
    assertThat(
            jdbc.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success = 1", String.class))
        .containsExactly("1");
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables"
                    + " WHERE table_schema = ? AND table_name IN ('lotto_winner_data',"
                    + " 'member_oauth_account')",
                Long.class,
                DB))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns"
                    + " WHERE table_schema = ? AND table_name = 'member' AND column_name = 'email'",
                Long.class,
                DB))
        .isZero();
  }
}
