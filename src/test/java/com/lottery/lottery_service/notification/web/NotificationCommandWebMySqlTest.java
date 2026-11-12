package com.lottery.lottery_service.notification.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lottery.lottery_service.account.AccountStore;
import com.lottery.lottery_service.auth.MemberPrincipal;
import com.lottery.lottery_service.auth.OAuthProvider;
import com.lottery.lottery_service.notification.verify.IssuedChallenge;
import com.lottery.lottery_service.notification.verify.VerificationStore;
import com.lottery.lottery_service.support.MySqlContainerSupport;
import com.lottery.lottery_service.support.OpenApiContract;
import jakarta.servlet.http.Cookie;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 인증 코드 확인과 알림 수신 동의·철회 API를 실제 보안 필터와 MySQL로 확인한다. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(OpenApiContract.Config.class)
class NotificationCommandWebMySqlTest extends MySqlContainerSupport {

  private static final String DB = "notification_command_web";
  private static final String PHONE = "+821012345678";

  @Autowired private MockMvc mvc;
  @Autowired private AccountStore accounts;
  @Autowired private VerificationStore verifications;
  @Autowired private PlatformTransactionManager tm;
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
    registry.add("server.servlet.session.cookie.name", () -> "__Host-lottery_session");
  }

  @Test
  @DisplayName("코드를 확인하면 연락처 버전을 주고, 그 버전으로 동의·철회한다. 다시 보내면 같은 명령, 옛 버전은 409, 다른 회원의 요청 ID는 410")
  void verifyThenConsent() throws Exception {
    MemberPrincipal member =
        MemberPrincipal.of(accounts.login(OAuthProvider.KAKAO, "notify"), OAuthProvider.KAKAO);
    MemberPrincipal other =
        MemberPrincipal.of(accounts.login(OAuthProvider.KAKAO, "notify-2"), OAuthProvider.KAKAO);
    IssuedChallenge challenge =
        new TransactionTemplate(tm).execute(s -> verifications.issue(member.memberId(), PHONE, 0));
    Client mine = client(member);
    Client theirs = client(other);

    theirs
        .verify(challenge.publicId(), challenge.code(), UUID.randomUUID())
        .andExpect(status().isGone())
        .andExpect(jsonPath("$.code").value("CHALLENGE_EXPIRED"));

    JsonNode verified =
        body(
            mine.verify(challenge.publicId(), challenge.code(), UUID.randomUUID())
                .andExpect(status().isOk())
                .andReturn());
    String version = verified.path("data").path("verifiedContactVersion").asText();
    assertThat(verified.path("status").asText()).isEqualTo("SUCCEEDED");

    UUID key = UUID.randomUUID();
    String token = mine.token("CHANGE_PREFERENCE");
    JsonNode granted =
        body(mine.change(true, version, key, token).andExpect(status().isOk()).andReturn());
    assertThat(granted.path("data").path("enabled").asBoolean()).isTrue();
    assertThat(granted.path("data").path("maskedPhoneNumber").asText()).isEqualTo("010-****-5678");
    assertThat(granted.path("data").path("policyVersion").asText()).isEqualTo("2026-11");
    JsonNode replay =
        body(mine.change(true, version, key, token).andExpect(status().isOk()).andReturn());
    assertThat(replay.path("operationId").asText()).isEqualTo(granted.path("operationId").asText());
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM consent_event WHERE member_id = ?",
                Long.class,
                member.memberId()))
        .isEqualTo(1);

    mine.change(false, version, UUID.randomUUID())
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("CONTACT_CHANGED"));
    String current = granted.path("data").path("version").asText();
    JsonNode withdrawn =
        body(mine.change(false, current, UUID.randomUUID()).andExpect(status().isOk()).andReturn());
    assertThat(withdrawn.path("data").path("enabled").asBoolean()).isFalse();

    theirs
        .change(true, "0", UUID.randomUUID())
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
  }

  private Client client(MemberPrincipal member) throws Exception {
    MvcResult state =
        mvc.perform(get("/api/v1/session").with(oauth2Login().oauth2User(member))).andReturn();
    String csrf = body(state).get("csrfToken").asText();
    String setCookie = state.getResponse().getHeader("Set-Cookie");
    Cookie session =
        new Cookie(
            "__Host-lottery_session",
            setCookie.substring(setCookie.indexOf('=') + 1, setCookie.indexOf(';')));
    return new Client(member, session, csrf);
  }

  private JsonNode body(MvcResult result) throws Exception {
    return json.readTree(result.getResponse().getContentAsString());
  }

  private final class Client {
    private final MemberPrincipal member;
    private final Cookie session;
    private final String csrf;

    Client(MemberPrincipal member, Cookie session, String csrf) {
      this.member = member;
      this.session = session;
      this.csrf = csrf;
    }

    String token(String operation) throws Exception {
      return body(mvc.perform(
                  post("/api/v1/command-tokens")
                      .cookie(session)
                      .with(oauth2Login().oauth2User(member))
                      .header("X-CSRF-TOKEN", csrf)
                      .contentType("application/json")
                      .content("{\"operation\":\"" + operation + "\"}"))
              .andExpect(status().isOk())
              .andReturn())
          .get("token")
          .asText();
    }

    org.springframework.test.web.servlet.ResultActions verify(UUID challenge, String code, UUID key)
        throws Exception {
      return mvc.perform(
          post("/api/v1/me/notification-verifications/" + challenge + "/verify")
              .cookie(session)
              .with(oauth2Login().oauth2User(member))
              .header("X-CSRF-TOKEN", csrf)
              .header("X-Command-Token", token("VERIFY_CONTACT"))
              .header("Idempotency-Key", key.toString())
              .contentType("application/json")
              .content("{\"code\":\"" + code + "\"}"));
    }

    org.springframework.test.web.servlet.ResultActions change(
        boolean enabled, String version, UUID key) throws Exception {
      return change(enabled, version, key, token("CHANGE_PREFERENCE"));
    }

    org.springframework.test.web.servlet.ResultActions change(
        boolean enabled, String version, UUID key, String token) throws Exception {
      return mvc.perform(
          put("/api/v1/me/notification-preference")
              .cookie(session)
              .with(oauth2Login().oauth2User(member))
              .header("X-CSRF-TOKEN", csrf)
              .header("X-Command-Token", token)
              .header("Idempotency-Key", key.toString())
              .contentType("application/json")
              .content(
                  "{\"enabled\":"
                      + enabled
                      + ",\"expectedVersion\":\""
                      + version
                      + "\",\"verifiedContactVersion\":\""
                      + version
                      + "\",\"policyVersion\":\"2026-11\"}"));
    }
  }
}
