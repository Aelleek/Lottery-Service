package com.lottery.lottery_service.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.lottery.lottery_service.support.MySqlContainerSupport;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 실제 내장 서버로 띄웠을 때 회원 세션 쿠키와 만료가 운영 설정(03 6.2, 02 7.2)대로인지 확인한다. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SessionCookieServerMySqlTest extends MySqlContainerSupport {

  private static final String DB = "session_cookie_server";

  @LocalServerPort private int port;
  @Autowired private JdbcTemplate jdbc;

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) throws SQLException {
    recreateDatabase(DB);
    registry.add("spring.datasource.url", () -> databaseUrl(DB));
    registry.add("spring.datasource.username", MYSQL::getUsername);
    registry.add("spring.datasource.password", MYSQL::getPassword);
    registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
    registry.add("spring.flyway.enabled", () -> "true");
    registry.add("spring.session.jdbc.initialize-schema", () -> "never");
    registry.add("lottery.api-v1.enabled", () -> "true");
    registry.add("server.servlet.session.cookie.name", () -> "__Host-lottery_session");
    registry.add("server.servlet.session.cookie.secure", () -> "true");
    registry.add("server.servlet.session.cookie.http-only", () -> "true");
    registry.add("server.servlet.session.cookie.same-site", () -> "Lax");
    registry.add("server.servlet.session.timeout", () -> "14d");
    registry.add("spring.session.timeout", () -> "14d");
  }

  @Test
  @DisplayName("내장 서버에서도 세션 쿠키는 __Host- 이름에 Secure·HttpOnly·SameSite=Lax이고, 서버 세션은 14일 미사용 만료다")
  void embeddedServerSessionCookie() throws Exception {
    HttpResponse<String> response =
        HttpClient.newHttpClient()
            .send(
                HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/oauth2/authorization/test"))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString());

    List<String> cookies = response.headers().allValues("Set-Cookie");
    String session =
        cookies.stream()
            .filter(c -> c.startsWith("__Host-lottery_session="))
            .findFirst()
            .orElseThrow(
                () ->
                    new AssertionError(
                        "세션 쿠키 없음: " + response.statusCode() + " " + response.headers().map()));
    assertThat(session).contains("; Path=/", "; Secure", "; HttpOnly", "; SameSite=Lax");
    assertThat(cookies).noneMatch(c -> c.startsWith("SESSION=") || c.startsWith("JSESSIONID="));

    String value = session.substring(session.indexOf('=') + 1, session.indexOf(';'));
    String id = new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
    assertThat(
            jdbc.queryForObject(
                "SELECT MAX_INACTIVE_INTERVAL FROM SPRING_SESSION WHERE SESSION_ID = ?",
                Integer.class,
                id))
        .isEqualTo(14 * 24 * 60 * 60);
  }

  @Test
  @DisplayName("등록되지 않은 제공자의 로그인 시작은 500이 아니고 서버 세션도 만들지 않는다")
  void unknownProviderIsNotServerError() throws Exception {
    long before = jdbc.queryForObject("SELECT COUNT(*) FROM SPRING_SESSION", Long.class);

    HttpResponse<String> response =
        HttpClient.newHttpClient()
            .send(
                HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/oauth2/authorization/no-such"))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isLessThan(500);
    assertThat(response.headers().firstValue("Location").orElse("")).doesNotContain("example.com");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM SPRING_SESSION", Long.class))
        .isEqualTo(before);
  }
}
