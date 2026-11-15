package com.lottery.lottery_service.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.lottery.lottery_service.support.MySqlContainerSupport;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.sql.SQLException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;

/** 제품 설정의 연결 초기화가 DB 서버 시간대와 상관없이 UTC·READ COMMITTED 세션을 만드는지 실제 MySQL로 확인한다. */
class DatasourceSessionMySqlTest extends MySqlContainerSupport {

  @Test
  @DisplayName("서울 시간대 세션으로 붙어도 제품 설정의 연결은 NOW()가 UTC이고 READ COMMITTED다")
  void productionSettingsForceUtcAndReadCommitted() throws IOException, SQLException {
    String path = "src/main/resources/application.yml";
    PropertySource<?> main =
        new YamlPropertySourceLoader().load(path, new FileSystemResource(path)).get(0);
    recreateDatabase("datasource_session");
    HikariConfig config = new HikariConfig();
    config.setJdbcUrl(
        MYSQL.getJdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/datasource_session$1")
            + (MYSQL.getJdbcUrl().contains("?") ? "&" : "?")
            + "connectionTimeZone=%2B09:00&forceConnectionTimeZoneToSession=true");
    config.setUsername(MYSQL.getUsername());
    config.setPassword(MYSQL.getPassword());
    config.setMaximumPoolSize(1);
    config.setConnectionInitSql(
        (String) main.getProperty("spring.datasource.hikari.connection-init-sql"));
    config.setTransactionIsolation(
        (String) main.getProperty("spring.datasource.hikari.transaction-isolation"));

    try (HikariDataSource ds = new HikariDataSource(config)) {
      JdbcTemplate jdbc = new JdbcTemplate(ds);
      assertThat(jdbc.queryForObject("SELECT @@session.time_zone", String.class))
          .isEqualTo("+00:00");
      assertThat(
              jdbc.queryForObject(
                  "SELECT TIMESTAMPDIFF(SECOND, UTC_TIMESTAMP(), NOW())", Long.class))
          .isZero();
      assertThat(jdbc.queryForObject("SELECT @@transaction_isolation", String.class))
          .isEqualTo("READ-COMMITTED");
    }
  }
}
