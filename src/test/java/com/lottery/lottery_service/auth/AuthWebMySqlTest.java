package com.lottery.lottery_service.auth;

import static org.assertj.core.api.Assertions.assertThat;
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
import com.lottery.lottery_service.support.MySqlContainerSupport;
import jakarta.servlet.http.Cookie;
import java.sql.SQLException;
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

/** 비회원 자격·세션 조회·CSRF·로그아웃을 실제 보안 필터와 MySQL로 확인한다. */
@SpringBootTest
@AutoConfigureMockMvc
class AuthWebMySqlTest extends MySqlContainerSupport {

  private static final String DB = "auth_web";
  private static final String GUEST_COOKIE = "__Host-lottery_guest";

  @Autowired private MockMvc mvc;
  @Autowired private AccountStore accounts;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private ObjectMapper json;

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
    mvc.perform(post("/api/v1/command-tokens").cookie(guest).header("X-CSRF-TOKEN", token))
        .andExpect(status().isNotFound());

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
  @DisplayName("회원 토큰은 그 세션에만 맞고, 로그아웃은 POST와 토큰이 필요하며 auth_version을 올린다")
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
    Cookie session =
        new Cookie(
            "__Host-lottery_session",
            setCookie.substring(setCookie.indexOf('=') + 1, setCookie.indexOf(';')));

    mvc.perform(
            post("/api/v1/guest-credentials")
                .cookie(session)
                .with(oauth2Login().oauth2User(principal)))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/logout").header("X-CSRF-TOKEN", token).with(oauth2Login().oauth2User(principal)))
        .andExpect(jsonPath("$.code").value("CSRF_REJECTED"));
    mvc.perform(get("/logout").cookie(session).with(oauth2Login().oauth2User(principal)));
    assertThat(authVersion(login.memberId())).isEqualTo(login.authVersion());

    mvc.perform(
            post("/logout")
                .cookie(session)
                .header("X-CSRF-TOKEN", token)
                .with(oauth2Login().oauth2User(principal)))
        .andExpect(status().is3xxRedirection());
    assertThat(authVersion(login.memberId())).isEqualTo(login.authVersion() + 1);

    mvc.perform(
            post("/api/v1/command-tokens")
                .header("X-CSRF-TOKEN", token)
                .with(oauth2Login().oauth2User(principal)))
        .andExpect(status().isUnauthorized());
  }

  private long authVersion(long memberId) {
    return jdbc.queryForObject(
        "SELECT auth_version FROM member WHERE id = ?", Long.class, memberId);
  }

  private JsonNode body(MvcResult result) throws Exception {
    return json.readTree(result.getResponse().getContentAsString());
  }
}
