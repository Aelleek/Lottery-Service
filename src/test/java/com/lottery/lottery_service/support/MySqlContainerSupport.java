package com.lottery.lottery_service.support;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import org.junit.jupiter.api.Tag;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 실제 MySQL 8.4 컨테이너를 쓰는 테스트의 기반. {@code mysql} 태그가 붙어 {@code ./gradlew mysqlTest}로만 돈다. 컨테이너는 JVM
 * 안에서 한 번만 띄워 여러 테스트 클래스가 함께 쓴다.
 */
@Tag("mysql")
public abstract class MySqlContainerSupport {

  /** 설계 기준 MySQL 이미지. */
  public static final DockerImageName IMAGE = DockerImageName.parse("mysql:8.4");

  protected static final MySQLContainer<?> MYSQL = new MySQLContainer<>(IMAGE);

  static {
    MYSQL.start();
  }

  /** 컨테이너 DB에 새 연결을 연다. */
  protected static Connection openConnection() throws SQLException {
    return DriverManager.getConnection(
        MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
  }
}
