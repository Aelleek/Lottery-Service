package com.lottery.lottery_service.support;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 실제 MySQL 8.4 컨테이너를 쓰는 테스트의 기반. {@code mysql} 태그가 붙어 {@code ./gradlew mysqlTest}로만 돈다. 컨테이너는 JVM
 * 안에서 한 번만 띄우고, 테스트 클래스마다 자기 DB를 만들어 서로 섞이지 않게 한다.
 */
@Tag("mysql")
public abstract class MySqlContainerSupport {

  /** 설계 기준 MySQL 이미지. */
  public static final DockerImageName IMAGE = DockerImageName.parse("mysql:8.4");

  protected static final MySQLContainer<?> MYSQL =
      new MySQLContainer<>(IMAGE).withUsername("root").withPassword("test");

  static {
    MYSQL.start();
  }

  /** 새 DB를 만든다. 같은 이름이 있으면 지우고 다시 만든다. */
  protected static void recreateDatabase(String name) throws SQLException {
    try (Connection c = openConnection(null);
        Statement st = c.createStatement()) {
      st.execute("DROP DATABASE IF EXISTS `" + name + "`");
      st.execute("CREATE DATABASE `" + name + "`");
    }
  }

  /** 새 DB를 만들고 제품 migration({@code db/migration})을 적용한 뒤 그 DB의 데이터소스를 돌려준다. */
  protected static DataSource migratedDatabase(String name) throws SQLException {
    recreateDatabase(name);
    Flyway.configure()
        .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
        .schemas(name)
        .createSchemas(false)
        .locations("classpath:db/migration")
        .load()
        .migrate();
    return new DriverManagerDataSource(
        MYSQL.getJdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/" + name + "$1"),
        MYSQL.getUsername(),
        MYSQL.getPassword());
  }

  /** 주어진 DB에 연결한다. {@code null}이면 컨테이너 기본 DB다. */
  protected static Connection openConnection(String database) throws SQLException {
    Connection c =
        DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    if (database != null) {
      c.setCatalog(database);
    }
    return c;
  }
}
