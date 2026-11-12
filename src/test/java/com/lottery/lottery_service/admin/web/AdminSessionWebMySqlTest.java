package com.lottery.lottery_service.admin.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lottery.lottery_service.account.AccountStore;
import com.lottery.lottery_service.admin.mfa.AdminMfaStore;
import com.lottery.lottery_service.admin.mfa.Totp;
import com.lottery.lottery_service.auth.MemberPrincipal;
import com.lottery.lottery_service.auth.OAuthProvider;
import com.lottery.lottery_service.support.MySqlContainerSupport;
import com.lottery.lottery_service.support.OpenApiContract;
import jakarta.servlet.http.Cookie;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** 회원 세션 안의 관리자 세션 시작, 관리자 API 앞 확인, 관리자 조회 API를 실제 보안 필터와 MySQL로 확인한다(2026-11-09 Q2). */
@SpringBootTest
@AutoConfigureMockMvc
@Import(OpenApiContract.Config.class)
class AdminSessionWebMySqlTest extends MySqlContainerSupport {

  private static final String DB = "admin_session_web";

  @Autowired private MockMvc mvc;
  @Autowired private AccountStore accounts;
  @Autowired private AdminMfaStore mfa;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private ObjectMapper json;
  @Autowired private SessionRepository<? extends Session> sessions;

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
  }

  @Test
  @DisplayName("관리자 권한이 없으면 관리자 인증과 관리자 API 모두 403 FORBIDDEN이다")
  void memberWithoutGrant() throws Exception {
    MemberPrincipal member =
        MemberPrincipal.of(accounts.login(OAuthProvider.KAKAO, "plain"), OAuthProvider.KAKAO);
    Login login = login(member);

    mvc.perform(
            login
                .apply(post("/api/v1/admin/session"))
                .contentType("application/json")
                .content("{\"code\":\"123456\"}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    mvc.perform(login.apply(get("/api/v1/admin/rounds/1200/notification-status")))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
  }

  @Test
  @DisplayName("관리자는 인증 앱 코드로 관리자 세션을 연 뒤에만 관리자 API를 지나고, 틀린 코드는 400, 세션 ID는 새로 만든다")
  void adminSessionGate() throws Exception {
    MemberPrincipal admin =
        MemberPrincipal.of(accounts.login(OAuthProvider.GOOGLE, "admin"), OAuthProvider.GOOGLE);
    jdbc.update(
        "INSERT INTO admin_grant (member_id, role, granted_at) VALUES (?, 'ADMIN', NOW(6))",
        admin.memberId());
    byte[] secret = decode(mfa.enroll(admin.memberId()).secret());
    long step = Totp.step(Instant.now());
    assertThat(mfa.confirm(admin.memberId(), Totp.code(secret, step - 1)))
        .isEqualTo(AdminMfaStore.Outcome.OK);
    Login login = login(admin);
    String probe = "/api/v1/admin/rounds/1200/no-such-admin-api";

    mvc.perform(login.apply(get(probe)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("ADMIN_AUTH_REQUIRED"));
    String wrong = "000000".equals(Totp.code(secret, step)) ? "111111" : "000000";
    mvc.perform(
            login
                .apply(post("/api/v1/admin/session"))
                .contentType("application/json")
                .content("{\"code\":\"" + wrong + "\"}"))
        .andExpect(status().isBadRequest());
    mvc.perform(
            login
                .apply(post("/api/v1/admin/session"))
                .contentType("application/json")
                .content("{\"code\":\"12345\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

    MvcResult started =
        mvc.perform(
                login
                    .apply(post("/api/v1/admin/session"))
                    .contentType("application/json")
                    .content("{\"code\":\"" + Totp.code(secret, step) + "\"}"))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.idleTimeoutSeconds").value(1800))
            .andReturn();
    Cookie renewed = sessionCookie(started.getResponse().getHeader("Set-Cookie"));
    assertThat(renewed.getValue()).isNotEqualTo(login.session().getValue());

    String newCsrf =
        json.readTree(started.getResponse().getContentAsString()).get("csrfToken").asText();
    Login admitted = new Login(admin, renewed, newCsrf);
    mvc.perform(admitted.apply(get(probe))).andExpect(status().isNotFound());
    mvc.perform(
            admitted
                .apply(post("/api/v1/command-tokens"))
                .contentType("application/json")
                .content("{\"operation\":\"WITHDRAW\"}"))
        .andExpect(status().isOk());
    mvc.perform(
            new Login(admin, renewed, login.csrf())
                .apply(post("/api/v1/command-tokens"))
                .contentType("application/json")
                .content("{\"operation\":\"WITHDRAW\"}"))
        .andExpect(jsonPath("$.code").value("CSRF_REJECTED"));

    jdbc.update(
        "INSERT INTO lotto_round (round_no, sales_open_at, sales_close_at, draw_at, schedule_version)"
            + " VALUES (1200, '2026-11-07 21:00:00', '2026-11-14 11:00:00', '2026-11-14 11:35:00', 1)"
            + " ON DUPLICATE KEY UPDATE round_no = round_no");
    mvc.perform(admitted.apply(get("/api/v1/admin/rounds/1200/notification-status")))
        .andExpect(status().isOk())
        .andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(jsonPath("$.resultState").value("NOT_PUBLISHED"))
        .andExpect(jsonPath("$.messageCounts.inFlight").value(0))
        .andExpect(jsonPath("$.messageCounts.retryWait").value(0));
    mvc.perform(admitted.apply(get("/api/v1/admin/rounds/9999/notification-status")))
        .andExpect(status().isNotFound());
    mvc.perform(
            admitted.apply(
                get("/api/v1/admin/stats/daily")
                    .param("from", "2026-11-01")
                    .param("to", "2026-11-07")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.days.length()").value(7))
        .andExpect(jsonPath("$.calculationVersion").isString());
    mvc.perform(
            admitted.apply(
                get("/api/v1/admin/stats/daily")
                    .param("from", "2026-11-x")
                    .param("to", "2026-11-07")))
        .andExpect(status().isBadRequest());
    mvc.perform(admitted.apply(get("/api/v1/admin/stats/rounds/1200")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rankCounts.losing").value(0))
        .andExpect(jsonPath("$.calculationVersion").isString());

    sessionAttribute(renewed, "lastRequestAt", Instant.now().minus(Duration.ofMinutes(31)));
    mvc.perform(admitted.apply(get(probe)))
        .andExpect(jsonPath("$.code").value("ADMIN_AUTH_REQUIRED"));
    assertThat(sessionValue(renewed, "authenticatedAt")).isNull();
  }

  @Test
  @DisplayName("관리자 세션은 계속 써도 인증한 지 8시간이 되면 실제 세션 저장소를 거친 요청에서 끝난다")
  void absoluteLimitThroughSessionStore() throws Exception {
    MemberPrincipal admin =
        MemberPrincipal.of(accounts.login(OAuthProvider.NAVER, "admin-8h"), OAuthProvider.NAVER);
    jdbc.update(
        "INSERT INTO admin_grant (member_id, role, granted_at) VALUES (?, 'OPERATOR', NOW(6))",
        admin.memberId());
    byte[] secret = decode(mfa.enroll(admin.memberId()).secret());
    long step = Totp.step(Instant.now());
    mfa.confirm(admin.memberId(), Totp.code(secret, step - 1));
    Login login = login(admin);
    MvcResult started =
        mvc.perform(
                login
                    .apply(post("/api/v1/admin/session"))
                    .contentType("application/json")
                    .content("{\"code\":\"" + Totp.code(secret, step) + "\"}"))
            .andExpect(status().isOk())
            .andReturn();
    Cookie renewed = sessionCookie(started.getResponse().getHeader("Set-Cookie"));
    Login admitted = new Login(admin, renewed, login.csrf());
    String probe = "/api/v1/admin/rounds/1200/no-such-admin-api";
    mvc.perform(admitted.apply(get(probe))).andExpect(status().isNotFound());

    sessionAttribute(
        renewed, "authenticatedAt", Instant.now().minus(Duration.ofHours(8)).minusSeconds(1));
    mvc.perform(admitted.apply(get(probe)))
        .andExpect(jsonPath("$.code").value("ADMIN_AUTH_REQUIRED"));
  }

  @SuppressWarnings("unchecked")
  private void sessionAttribute(Cookie cookie, String name, Instant value) {
    SessionRepository<Session> repository = (SessionRepository<Session>) sessions;
    Session stored = repository.findById(sessionId(cookie));
    stored.setAttribute("com.lottery.lottery_service.admin.AdminSession." + name, value);
    repository.save(stored);
  }

  @SuppressWarnings("unchecked")
  private Object sessionValue(Cookie cookie, String name) {
    SessionRepository<Session> repository = (SessionRepository<Session>) sessions;
    return repository
        .findById(sessionId(cookie))
        .getAttribute("com.lottery.lottery_service.admin.AdminSession." + name);
  }

  private static String sessionId(Cookie cookie) {
    return new String(Base64.getDecoder().decode(cookie.getValue()), StandardCharsets.UTF_8);
  }

  private Login login(MemberPrincipal member) throws Exception {
    MvcResult state =
        mvc.perform(get("/api/v1/session").with(oauth2Login().oauth2User(member))).andReturn();
    String csrf = json.readTree(state.getResponse().getContentAsString()).get("csrfToken").asText();
    return new Login(member, sessionCookie(state.getResponse().getHeader("Set-Cookie")), csrf);
  }

  private static Cookie sessionCookie(String setCookie) {
    return new Cookie(
        "__Host-lottery_session",
        setCookie.substring(setCookie.indexOf('=') + 1, setCookie.indexOf(';')));
  }

  private static byte[] decode(String base32) {
    String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    int buffer = 0;
    int bits = 0;
    for (char c : base32.toCharArray()) {
      buffer = (buffer << 5) | alphabet.indexOf(c);
      bits += 5;
      if (bits >= 8) {
        out.write((buffer >> (bits - 8)) & 0xff);
        bits -= 8;
      }
    }
    return out.toByteArray();
  }

  private record Login(MemberPrincipal member, Cookie session, String csrf) {
    MockHttpServletRequestBuilder apply(MockHttpServletRequestBuilder request) {
      return request
          .cookie(session)
          .with(oauth2Login().oauth2User(member))
          .header("X-CSRF-TOKEN", csrf);
    }
  }
}
