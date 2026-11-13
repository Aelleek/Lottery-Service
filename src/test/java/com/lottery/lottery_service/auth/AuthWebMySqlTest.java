package com.lottery.lottery_service.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lottery.lottery_service.account.AccountStore;
import com.lottery.lottery_service.account.MemberLogin;
import com.lottery.lottery_service.account.withdrawal.RestoreReplay;
import com.lottery.lottery_service.account.withdrawal.SessionRevokeCommand;
import com.lottery.lottery_service.config.SecurityConfig;
import com.lottery.lottery_service.support.MySqlContainerSupport;
import com.lottery.lottery_service.support.OpenApiContract;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** 비회원 자격·세션 조회·CSRF·로그아웃을 실제 보안 필터와 MySQL로 확인한다. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(OpenApiContract.Config.class)
class AuthWebMySqlTest extends MySqlContainerSupport {

  private static final String DB = "auth_web";
  private static final String GUEST_COOKIE = "__Host-lottery_guest";

  @Autowired private MockMvc mvc;
  @Autowired private AccountStore accounts;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private ObjectMapper json;
  @Autowired private RestoreReplay replay;

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
    // 테스트 설정(src/test/resources/application.yml)이 제품 설정을 가리므로 운영 쿠키 설정을 다시 넣는다.
    // 제품 설정 값은 ProductionConfigTest가 확인한다.
    registry.add("server.servlet.session.cookie.name", () -> "__Host-lottery_session");
    registry.add("server.servlet.session.cookie.secure", () -> "true");
    registry.add("server.servlet.session.cookie.http-only", () -> "true");
    registry.add("server.servlet.session.cookie.same-site", () -> "Lax");
  }

  @Test
  @DisplayName("비회원 자격을 받으면 __Host 쿠키와 본문 CSRF 토큰을 주고, 토큰 없는 변경 요청은 CSRF_REJECTED다")
  void guestCredentialAndCsrf() throws Exception {
    MvcResult issued =
        mvc.perform(post("/api/v1/guest-credentials"))
            .andExpect(status().isCreated())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andReturn();
    String setCookie = issued.getResponse().getHeader("Set-Cookie");
    assertThat(setCookie)
        .startsWith(GUEST_COOKIE + "=")
        .contains("Path=/", "Secure", "HttpOnly", "SameSite=Lax");
    Cookie guest = issued.getResponse().getCookie(GUEST_COOKIE);
    String token = body(issued).get("csrfToken").asText();

    mvc.perform(get("/api/v1/session").cookie(guest))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.kind").value("GUEST"));

    mvc.perform(post("/api/v1/command-tokens").cookie(guest))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("CSRF_REJECTED"));
    mvc.perform(post("/api/v1/command-tokens").cookie(guest).header("X-CSRF-TOKEN", "v1.1.AAAA"))
        .andExpect(jsonPath("$.code").value("CSRF_REJECTED"));
    mvc.perform(
            post("/api/v1/command-tokens")
                .cookie(guest)
                .header("X-CSRF-TOKEN", token)
                .contentType("application/json")
                .content("{\"operation\":\"RECOMMEND\",\"round\":1200,\"scheduleVersion\":\"1\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.token").isString())
        .andExpect(header().string("Cache-Control", "no-store"));
    mvc.perform(
            post("/api/v1/command-tokens")
                .cookie(guest)
                .header("X-CSRF-TOKEN", token)
                .contentType("application/json")
                .content("{\"operation\":\"REGISTER_PURCHASE\"}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));

    MvcResult again =
        mvc.perform(post("/api/v1/guest-credentials").cookie(guest))
            .andExpect(status().isCreated())
            .andReturn();
    assertThat(again.getResponse().getHeader("Set-Cookie")).isNull();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM actor_subject", Long.class)).isEqualTo(1);
  }

  @Test
  @DisplayName("다른 사이트에서 온 변경 요청은 ORIGIN_REJECTED다")
  void crossSiteRejected() throws Exception {
    mvc.perform(post("/api/v1/guest-credentials").header("Sec-Fetch-Site", "cross-site"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("ORIGIN_REJECTED"));
    mvc.perform(post("/api/v1/guest-credentials").header("Origin", "https://evil.example"))
        .andExpect(jsonPath("$.code").value("ORIGIN_REJECTED"));
  }

  @Test
  @DisplayName("주체 없이 보호된 API를 부르면 401 UNAUTHENTICATED이고 세션 조회는 NONE이다")
  void anonymous() throws Exception {
    mvc.perform(post("/api/v1/command-tokens"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    mvc.perform(get("/api/v1/session"))
        .andExpect(jsonPath("$.kind").value("NONE"))
        .andExpect(jsonPath("$.csrfToken").doesNotExist());
  }

  @Test
  @DisplayName("회원 토큰은 그 세션에만 맞고, 로그아웃은 POST와 토큰이 필요하며 그 세션만 지운다")
  void memberSessionAndLogout() throws Exception {
    MemberLogin login = accounts.login(OAuthProvider.KAKAO, "web-" + System.nanoTime());
    MemberPrincipal principal = MemberPrincipal.of(login, OAuthProvider.KAKAO);
    MvcResult state =
        mvc.perform(get("/api/v1/session").with(oauth2Login().oauth2User(principal)))
            .andExpect(jsonPath("$.kind").value("MEMBER"))
            .andReturn();
    String token = body(state).get("csrfToken").asText();
    String setCookie = state.getResponse().getHeader("Set-Cookie");
    assertThat(setCookie)
        .startsWith("__Host-lottery_session=")
        .contains("Path=/", "Secure", "HttpOnly", "SameSite=Lax");
    Cookie session = sessionCookie(setCookie);
    Cookie otherDevice =
        sessionCookie(
            mvc.perform(get("/api/v1/session").with(oauth2Login().oauth2User(principal)))
                .andReturn()
                .getResponse()
                .getHeader("Set-Cookie"));

    mvc.perform(
            post("/api/v1/guest-credentials")
                .cookie(session)
                .with(oauth2Login().oauth2User(principal)))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/logout").header("X-CSRF-TOKEN", token).with(oauth2Login().oauth2User(principal)))
        .andExpect(jsonPath("$.code").value("CSRF_REJECTED"));
    mvc.perform(get("/logout").cookie(session).with(oauth2Login().oauth2User(principal)));
    assertThat(sessionExists(session)).isTrue();
    assertThat(sessionExists(otherDevice)).isTrue();

    mvc.perform(
            post("/logout")
                .cookie(session)
                .header("X-CSRF-TOKEN", token)
                .with(oauth2Login().oauth2User(principal)))
        .andExpect(status().is3xxRedirection());

    assertThat(authVersion(login.memberId())).isEqualTo(login.authVersion());
    assertThat(sessionExists(session)).isFalse();
    assertThat(sessionExists(otherDevice)).isTrue();
    mvc.perform(
            get("/api/v1/session").cookie(otherDevice).with(oauth2Login().oauth2User(principal)))
        .andExpect(jsonPath("$.kind").value("MEMBER"));
  }

  @Test
  @DisplayName("사고 대응 세션 회수 명령은 서버 세션을 지우고, 옛 인증 버전의 로그인 정보로 온 요청은 401이다(07 7절 2번)")
  void incidentRevokesEverySession() throws Exception {
    MemberLogin login = accounts.login(OAuthProvider.KAKAO, "incident-" + System.nanoTime());
    MemberPrincipal principal = MemberPrincipal.of(login, OAuthProvider.KAKAO);
    Cookie session =
        sessionCookie(
            mvc.perform(get("/api/v1/session").with(oauth2Login().oauth2User(principal)))
                .andReturn()
                .getResponse()
                .getHeader("Set-Cookie"));
    assertThat(sessionExists(session)).isTrue();
    mvc.perform(get("/api/v1/me/guest-claims").with(oauth2Login().oauth2User(principal)))
        .andExpect(status().isOk());

    assertThat(
            new SessionRevokeCommand(replay, mock(ConfigurableApplicationContext.class)).execute())
        .isZero();

    assertThat(sessionExists(session)).isFalse();
    assertThat(authVersion(login.memberId())).isEqualTo(login.authVersion() + 1);
    mvc.perform(get("/api/v1/me/guest-claims").with(oauth2Login().oauth2User(principal)))
        .andExpect(status().isUnauthorized());
  }

  private static Cookie sessionCookie(String setCookie) {
    return new Cookie(
        "__Host-lottery_session",
        setCookie.substring(setCookie.indexOf('=') + 1, setCookie.indexOf(';')));
  }

  private boolean sessionExists(Cookie cookie) {
    String id = new String(Base64.getDecoder().decode(cookie.getValue()), StandardCharsets.UTF_8);
    return jdbc.queryForObject(
            "SELECT COUNT(*) FROM SPRING_SESSION WHERE SESSION_ID = ?", Long.class, id)
        == 1;
  }

  @Test
  @DisplayName("모든 응답에 보안 헤더를 붙인다(C-29)")
  void securityHeaders() throws Exception {
    mvc.perform(get("/api/v1/session").secure(true))
        .andExpect(
            header().string("Content-Security-Policy", SecurityConfig.CONTENT_SECURITY_POLICY))
        .andExpect(header().string("X-Frame-Options", "DENY"))
        .andExpect(header().string("X-Content-Type-Options", "nosniff"))
        .andExpect(header().string("Referrer-Policy", "strict-origin-when-cross-origin"))
        .andExpect(
            header().string("Permissions-Policy", "camera=(self), microphone=(), geolocation=()"))
        .andExpect(
            header().string("Strict-Transport-Security", "max-age=31536000 ; includeSubDomains"))
        .andExpect(header().string("Cache-Control", "no-store"));
  }

  private long authVersion(long memberId) {
    return jdbc.queryForObject(
        "SELECT auth_version FROM member WHERE id = ?", Long.class, memberId);
  }

  private JsonNode body(MvcResult result) throws Exception {
    return json.readTree(result.getResponse().getContentAsString());
  }
}
