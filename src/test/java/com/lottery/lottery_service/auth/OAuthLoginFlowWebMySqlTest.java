package com.lottery.lottery_service.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lottery.lottery_service.support.MySqlContainerSupport;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import jakarta.servlet.http.Cookie;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 가짜 제공자로 실제 소셜 로그인 흐름(인가 이동 → 콜백 → 토큰 교환 → 사용자 정보 → 회원 로그인)을 끝까지 돈다. 로그인에 성공하면 세션 ID를 새로 만들어 로그인 전
 * 세션 ID를 쓸 수 없는지(세션 고정 방지, 03 6.3) 확인한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OAuthLoginFlowWebMySqlTest extends MySqlContainerSupport {

  private static final String DB = "oauth_login_flow";
  private static final String SESSION_COOKIE = "__Host-lottery_session";
  private static final List<String> TOKEN_REQUESTS = new CopyOnWriteArrayList<>();
  private static final AtomicReference<String> PROVIDER_USER = new AtomicReference<>("4242");
  private static final HttpServer PROVIDER = provider();

  @Autowired private MockMvc mvc;
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
    registry.add("server.servlet.session.cookie.name", () -> SESSION_COOKIE);
    registry.add("server.servlet.session.cookie.secure", () -> "true");
    registry.add("server.servlet.session.cookie.http-only", () -> "true");
    registry.add("server.servlet.session.cookie.same-site", () -> "Lax");
    String base = "http://127.0.0.1:" + PROVIDER.getAddress().getPort();
    String prefix = "spring.security.oauth2.client.";
    registry.add(prefix + "registration.kakao.client-id", () -> "fake-client");
    registry.add(prefix + "registration.kakao.client-secret", () -> "fake-secret");
    registry.add(
        prefix + "registration.kakao.client-authentication-method", () -> "client_secret_post");
    registry.add(
        prefix + "registration.kakao.authorization-grant-type", () -> "authorization_code");
    registry.add(
        prefix + "registration.kakao.redirect-uri",
        () -> "{baseUrl}/login/oauth2/code/{registrationId}");
    registry.add(prefix + "provider.kakao.authorization-uri", () -> base + "/authorize");
    registry.add(prefix + "provider.kakao.token-uri", () -> base + "/token");
    registry.add(prefix + "provider.kakao.user-info-uri", () -> base + "/userinfo");
    registry.add(prefix + "provider.kakao.user-name-attribute", () -> "id");
  }

  @AfterAll
  static void stopProvider() {
    PROVIDER.stop(0);
  }

  @Test
  @DisplayName("소셜 로그인에 성공하면 회원이 되고, 세션 ID가 바뀌어 로그인 전 세션 ID는 더 이상 없다")
  void loginRotatesSessionId() throws Exception {
    PROVIDER_USER.set("4242");
    MvcResult start = mvc.perform(get("/oauth2/authorization/kakao")).andReturn();
    assertThat(start.getResponse().getStatus()).isEqualTo(302);
    String location = start.getResponse().getHeader("Location");
    assertThat(location).startsWith("http://127.0.0.1:");
    Cookie before = sessionCookie(start);
    assertThat(sessionExists(before)).isTrue();

    MvcResult callback =
        mvc.perform(
                get("/login/oauth2/code/kakao")
                    .param("code", "fake-code")
                    .param("state", query(location, "state"))
                    .cookie(before))
            .andExpect(status().is3xxRedirection())
            .andReturn();

    assertThat(callback.getResponse().getHeader("Location")).doesNotContain("error");
    Cookie after = sessionCookie(callback);
    assertThat(after.getValue()).isNotEqualTo(before.getValue());
    assertThat(sessionExists(before)).isFalse();
    assertThat(sessionExists(after)).isTrue();
    assertThat(TOKEN_REQUESTS).last().asString().contains("code=fake-code");
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM oauth_account WHERE provider_user_id = '4242'", Long.class))
        .isEqualTo(1);

    mvc.perform(get("/api/v1/session").cookie(after)).andExpect(jsonPath("$.kind").value("MEMBER"));
    mvc.perform(get("/api/v1/session").cookie(before)).andExpect(jsonPath("$.kind").value("NONE"));
  }

  @Test
  @DisplayName(
      "T62: 재인증은 제공자에 로그인 화면을 다시 요청하고, 같은 계정이면 재인증 시각을 남긴다."
          + " 다른 계정이면 실패로 돌려보내고 세션은 원래 회원 그대로이며 그 계정의 회원을 새로 만들지 않는다")
  void reauthenticationThroughProvider() throws Exception {
    PROVIDER_USER.set("5001");
    Cookie session = login();
    assertThat(reauthenticatedAt(session)).isFalse();

    PROVIDER_USER.set("5002");
    MvcResult other = reauth(session);
    assertThat(other.getResponse().getHeader("Location")).endsWith("/?reauth=failed");
    assertThat(reauthenticatedAt(session)).isFalse();
    mvc.perform(get("/api/v1/session").cookie(session))
        .andExpect(jsonPath("$.kind").value("MEMBER"));
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM oauth_account WHERE provider_user_id = '5002'", Long.class))
        .isZero();

    PROVIDER_USER.set("5001");
    MvcResult same = reauth(session);
    assertThat(same.getResponse().getHeader("Location")).doesNotContain("failed");
    Cookie rotated = sessionCookie(same);
    assertThat(reauthenticatedAt(rotated)).isTrue();
    mvc.perform(get("/api/v1/session").cookie(rotated))
        .andExpect(jsonPath("$.kind").value("MEMBER"));
  }

  private Cookie login() throws Exception {
    MvcResult start = mvc.perform(get("/oauth2/authorization/kakao")).andReturn();
    Cookie before = sessionCookie(start);
    MvcResult callback =
        mvc.perform(
                get("/login/oauth2/code/kakao")
                    .param("code", "fake-code")
                    .param("state", query(start.getResponse().getHeader("Location"), "state"))
                    .cookie(before))
            .andExpect(status().is3xxRedirection())
            .andReturn();
    return sessionCookie(callback);
  }

  private MvcResult reauth(Cookie session) throws Exception {
    MvcResult start =
        mvc.perform(get("/oauth2/authorization/kakao").param("reauth", "true").cookie(session))
            .andReturn();
    String location = start.getResponse().getHeader("Location");
    assertThat(query(location, "prompt")).isEqualTo("login");
    return mvc.perform(
            get("/login/oauth2/code/kakao")
                .param("code", "fake-code")
                .param("state", query(location, "state"))
                .cookie(session))
        .andExpect(status().is3xxRedirection())
        .andReturn();
  }

  private boolean reauthenticatedAt(Cookie cookie) {
    String id = new String(Base64.getDecoder().decode(cookie.getValue()), StandardCharsets.UTF_8);
    return jdbc.queryForObject(
            "SELECT COUNT(*) FROM SPRING_SESSION_ATTRIBUTES a JOIN SPRING_SESSION s"
                + " ON s.PRIMARY_ID = a.SESSION_PRIMARY_ID WHERE s.SESSION_ID = ?"
                + " AND a.ATTRIBUTE_NAME = ?",
            Long.class,
            id,
            Reauthentication.COMPLETED_AT)
        > 0;
  }

  private static HttpServer provider() {
    try {
      HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext(
          "/token",
          exchange -> {
            TOKEN_REQUESTS.add(
                new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            json(
                exchange,
                "{\"access_token\":\"fake-at\",\"token_type\":\"bearer\",\"expires_in\":3600}");
          });
      server.createContext(
          "/userinfo",
          exchange -> json(exchange, "{\"id\":" + PROVIDER_USER.get() + ",\"kakao_account\":{}}"));
      server.start();
      return server;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static void json(HttpExchange exchange, String body) throws IOException {
    byte[] out = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(200, out.length);
    exchange.getResponseBody().write(out);
    exchange.close();
  }

  private static String query(String url, String name) {
    for (String pair : url.substring(url.indexOf('?') + 1).split("&")) {
      if (pair.startsWith(name + "=")) {
        return URLDecoder.decode(pair.substring(name.length() + 1), StandardCharsets.UTF_8);
      }
    }
    throw new IllegalStateException("missing query parameter");
  }

  private static Cookie sessionCookie(MvcResult result) {
    String setCookie =
        result.getResponse().getHeaders("Set-Cookie").stream()
            .filter(c -> c.startsWith(SESSION_COOKIE + "="))
            .reduce((first, second) -> second)
            .orElseThrow();
    return new Cookie(
        SESSION_COOKIE, setCookie.substring(setCookie.indexOf('=') + 1, setCookie.indexOf(';')));
  }

  private boolean sessionExists(Cookie cookie) {
    String id = new String(Base64.getDecoder().decode(cookie.getValue()), StandardCharsets.UTF_8);
    return jdbc.queryForObject(
            "SELECT COUNT(*) FROM SPRING_SESSION WHERE SESSION_ID = ?", Long.class, id)
        == 1;
  }
}
