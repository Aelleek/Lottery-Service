package com.lottery.lottery_service.account.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lottery.lottery_service.account.AccountStore;
import com.lottery.lottery_service.auth.MemberPrincipal;
import com.lottery.lottery_service.auth.OAuthProvider;
import com.lottery.lottery_service.support.MySqlContainerSupport;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** 탈퇴 API가 최근 재인증 없이는 받지 않는지 실제 보안 필터와 MySQL로 확인한다. */
@SpringBootTest
@AutoConfigureMockMvc
class AccountWebMySqlTest extends MySqlContainerSupport {

  private static final String DB = "account_web";

  @Autowired private MockMvc mvc;
  @Autowired private AccountStore accounts;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private ObjectMapper json;

  @SuppressWarnings("rawtypes")
  @Autowired
  private SessionRepository sessions;

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
  @DisplayName("재인증 기록이 없는 세션의 탈퇴는 403 REAUTH_REQUIRED이고 회원은 그대로 ACTIVE다")
  void withdrawRequiresReauthentication() throws Exception {
    MemberPrincipal member =
        MemberPrincipal.of(accounts.login(OAuthProvider.NAVER, "leaver"), OAuthProvider.NAVER);
    MvcResult state =
        mvc.perform(get("/api/v1/session").with(oauth2Login().oauth2User(member))).andReturn();
    String csrf = json.readTree(state.getResponse().getContentAsString()).get("csrfToken").asText();
    String setCookie = state.getResponse().getHeader("Set-Cookie");
    Cookie session =
        new Cookie(
            "__Host-lottery_session",
            setCookie.substring(setCookie.indexOf('=') + 1, setCookie.indexOf(';')));
    String token =
        json.readTree(
                mvc.perform(
                        post("/api/v1/command-tokens")
                            .cookie(session)
                            .with(oauth2Login().oauth2User(member))
                            .header("X-CSRF-TOKEN", csrf)
                            .contentType("application/json")
                            .content("{\"operation\":\"WITHDRAW\"}"))
                    .andReturn()
                    .getResponse()
                    .getContentAsString())
            .get("token")
            .asText();

    mvc.perform(
            delete("/api/v1/me/account")
                .cookie(session)
                .with(oauth2Login().oauth2User(member))
                .header("X-CSRF-TOKEN", csrf)
                .header("X-Command-Token", token)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType("application/json")
                .content("{\"expectedVersion\":\"0\"}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("REAUTH_REQUIRED"));
    assertThat(
            jdbc.queryForObject(
                "SELECT state FROM member WHERE id = ?", String.class, member.memberId()))
        .isEqualTo("ACTIVE");
  }

  @Test
  @DisplayName("재인증한 세션의 탈퇴는 202, 접근 차단·삭제 요청 상태를 주고 세션을 끝내며 회원은 WITHDRAWING이다")
  void withdrawAccepted() throws Exception {
    MemberPrincipal member =
        MemberPrincipal.of(accounts.login(OAuthProvider.NAVER, "leaver-ok"), OAuthProvider.NAVER);
    MvcResult state =
        mvc.perform(get("/api/v1/session").with(oauth2Login().oauth2User(member))).andReturn();
    String csrf = json.readTree(state.getResponse().getContentAsString()).get("csrfToken").asText();
    String setCookie = state.getResponse().getHeader("Set-Cookie");
    String cookieValue = setCookie.substring(setCookie.indexOf('=') + 1, setCookie.indexOf(';'));
    Cookie session = new Cookie("__Host-lottery_session", cookieValue);
    String sessionId =
        new String(java.util.Base64.getDecoder().decode(cookieValue), StandardCharsets.UTF_8);
    Session stored = sessions.findById(sessionId);
    stored.setAttribute(
        "com.lottery.lottery_service.auth.Reauthentication.completedAt", Instant.now());
    saveSession(stored);
    String token =
        json.readTree(
                mvc.perform(
                        post("/api/v1/command-tokens")
                            .cookie(session)
                            .with(oauth2Login().oauth2User(member))
                            .header("X-CSRF-TOKEN", csrf)
                            .contentType("application/json")
                            .content("{\"operation\":\"WITHDRAW\"}"))
                    .andReturn()
                    .getResponse()
                    .getContentAsString())
            .get("token")
            .asText();

    mvc.perform(
            delete("/api/v1/me/account")
                .cookie(session)
                .with(oauth2Login().oauth2User(member))
                .header("X-CSRF-TOKEN", csrf)
                .header("X-Command-Token", token)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType("application/json")
                .content("{\"expectedVersion\":\"0\"}"))
        .andExpect(status().isAccepted())
        .andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(jsonPath("$.status").value("PROCESSING"))
        .andExpect(jsonPath("$.data.accessBlocked").value(true))
        .andExpect(jsonPath("$.data.deletionState").value("REQUESTED"));
    assertThat(
            jdbc.queryForObject(
                "SELECT state FROM member WHERE id = ?", String.class, member.memberId()))
        .isEqualTo("WITHDRAWING");
    assertThat(sessions.findById(sessionId)).isNull();
  }

  @SuppressWarnings("unchecked")
  private void saveSession(Session session) {
    sessions.save(session);
  }
}
