package com.lottery.lottery_service.notification.web;

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
import com.lottery.lottery_service.auth.MemberPrincipal;
import com.lottery.lottery_service.auth.OAuthProvider;
import com.lottery.lottery_service.notification.SendOutcome;
import com.lottery.lottery_service.notification.verify.VerificationMessageStore;
import com.lottery.lottery_service.notification.verify.VerificationSender;
import com.lottery.lottery_service.support.MySqlContainerSupport;
import com.lottery.lottery_service.support.OpenApiContract;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/** 인증 코드 발급 API를 실제 보안 필터, MySQL, 가짜 발송기로 확인한다(06 1절, 02 6.4·6.5절, 05 8.8절). */
@SpringBootTest
@AutoConfigureMockMvc
@Import(OpenApiContract.Config.class)
@ExtendWith(OutputCaptureExtension.class)
class VerificationIssueWebMySqlTest extends MySqlContainerSupport {

  private static final String DB = "verification_issue_web";

  /** 보낸 코드를 기록하고, 정한 결과로 답하는 가짜 발송기. */
  static final class FakeSender implements VerificationSender {
    final List<String> codes = new ArrayList<>();
    final AtomicReference<SendOutcome> next =
        new AtomicReference<>(new SendOutcome.Accepted("M-1"));
    final AtomicReference<Runnable> during = new AtomicReference<>(() -> {});

    @Override
    public synchronized SendOutcome send(long messageId, String code) {
      codes.add(code);
      during.get().run();
      return next.get();
    }
  }

  @TestConfiguration
  static class Senders {
    @Bean
    @Primary
    FakeSender fakeSender() {
      return new FakeSender();
    }
  }

  @Autowired private MockMvc mvc;
  @Autowired private AccountStore accounts;
  @Autowired private FakeSender sender;
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

  @BeforeEach
  void reset() {
    sender.codes.clear();
    sender.next.set(new SendOutcome.Accepted("M-1"));
    sender.during.set(() -> {});
    jdbc.update("UPDATE message_budget SET limit_units = 300000");
  }

  @Test
  @DisplayName(
      "발송 중 다른 재시도가 처리 권한을 넘겨받아도 발송 결과는 남고 503과 Retry-After로 답한다. 같은 키는 처리 중으로 답하고 다시 보내지 않는다")
  void leaseLostAfterSend() throws Exception {
    Client mine = client("issue-lease");
    UUID key = UUID.randomUUID();
    String token = mine.token("REQUEST_VERIFICATION");
    sender.during.set(
        () ->
            jdbc.update(
                "UPDATE command_operation SET lease_token = UUID_TO_BIN(UUID()),"
                    + " lease_until = DATE_ADD(UTC_TIMESTAMP(6), INTERVAL 1 MINUTE)"
                    + " WHERE client_key = UUID_TO_BIN(?)",
                key.toString()));

    mine.request("+821012120000", "0", key, token)
        .andExpect(status().isServiceUnavailable())
        .andExpect(header().exists("Retry-After"))
        .andExpect(jsonPath("$.code").value("TEMPORARILY_UNAVAILABLE"));
    assertThat(
            jdbc.queryForMap(
                "SELECT state, cost_state FROM delivery_message WHERE member_id = ?",
                mine.member.memberId()))
        .containsEntry("state", "ACCEPTED")
        .containsEntry("cost_state", "SPENT");

    sender.during.set(() -> {});
    mine.request("+821012120000", "0", key, token).andExpect(status().isAccepted());
    assertThat(sender.codes).hasSize(1);
  }

  @Test
  @DisplayName("발급하면 코드를 한 번 보내고 5분 기한·60초 뒤 재요청 시각을 준다. 그 코드로 확인된다. 같은 키는 같은 요청, 60초 안 새 요청은 429")
  void issueSendAndVerify() throws Exception {
    Client mine = client("issue-ok");
    UUID key = UUID.randomUUID();
    String token = mine.token("REQUEST_VERIFICATION");

    JsonNode issued =
        body(
            mine.request("+821011112222", "0", key, token)
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andReturn());
    assertThat(issued.path("status").asText()).isEqualTo("SUCCEEDED");
    UUID challenge = UUID.fromString(issued.path("data").path("challengeId").asText());
    Instant expiresAt = Instant.parse(issued.path("data").path("expiresAt").asText());
    Instant resendAt = Instant.parse(issued.path("data").path("resendAt").asText());
    assertThat(Duration.between(resendAt, expiresAt)).isEqualTo(Duration.ofMinutes(4));
    assertThat(sender.codes).hasSize(1);
    assertThat(sender.codes.get(0)).matches("[0-9]{6}");

    Map<String, Object> message =
        jdbc.queryForMap(
            "SELECT m.state, m.cost_state, m.cost_units, m.provider_message_id,"
                + " m.payload_ciphertext, m.round_no, m.recipient_ciphertext IS NOT NULL AS has_to"
                + " FROM delivery_message m JOIN verification_challenge c ON c.id = m.challenge_id"
                + " WHERE c.public_id = UUID_TO_BIN(?)",
            challenge.toString());
    assertThat(message)
        .containsEntry("state", "ACCEPTED")
        .containsEntry("cost_state", "SPENT")
        .containsEntry("cost_units", VerificationMessageStore.COST_UNITS)
        .containsEntry("provider_message_id", "M-1")
        .containsEntry("payload_ciphertext", null)
        .containsEntry("round_no", null)
        .containsEntry("has_to", 1L);

    JsonNode replay =
        body(mine.request("+821011112222", "0", key, token).andExpect(status().isOk()).andReturn());
    assertThat(replay.path("operationId").asText()).isEqualTo(issued.path("operationId").asText());
    assertThat(replay.path("data").path("challengeId").asText()).isEqualTo(challenge.toString());
    assertThat(sender.codes).hasSize(1);

    mine.request("+821011112222", "0", UUID.randomUUID())
        .andExpect(status().isTooManyRequests())
        .andExpect(header().exists("Retry-After"))
        .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
    assertThat(sender.codes).hasSize(1);

    mine.verify(challenge, sender.codes.get(0))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.verifiedContactVersion").value("0"));
  }

  @Test
  @DisplayName("제공자가 받지 않으면 503이고 그 코드는 무효, 비용은 반환된다. 접수 여부를 모르면 성공으로 답하고 예약을 둔 채 대조를 기다린다")
  void providerOutcomes() throws Exception {
    Client refused = client("issue-refused");
    sender.next.set(new SendOutcome.NotAccepted("PROVIDER_NOT_CONFIGURED"));
    refused
        .request("+821033334444", "0", UUID.randomUUID())
        .andExpect(status().isServiceUnavailable())
        .andExpect(header().exists("Retry-After"))
        .andExpect(jsonPath("$.code").value("TEMPORARILY_UNAVAILABLE"));
    Map<String, Object> failed =
        jdbc.queryForMap(
            "SELECT m.state, m.cost_state, m.last_error_code, c.state AS challenge_state,"
                + " c.code_hmac FROM delivery_message m JOIN verification_challenge c"
                + " ON c.id = m.challenge_id WHERE m.member_id = ?",
            refused.member.memberId());
    assertThat(failed)
        .containsEntry("state", "FAILED")
        .containsEntry("cost_state", "RELEASED")
        .containsEntry("last_error_code", "PROVIDER_NOT_CONFIGURED")
        .containsEntry("challenge_state", "REVOKED")
        .containsEntry("code_hmac", null);

    Client unknown = client("issue-unknown");
    sender.next.set(new SendOutcome.Unknown());
    unknown.request("+821055556666", "0", UUID.randomUUID()).andExpect(status().isOk());
    assertThat(
            jdbc.queryForMap(
                "SELECT state, cost_state FROM delivery_message WHERE member_id = ?",
                unknown.member.memberId()))
        .containsEntry("state", "UNKNOWN")
        .containsEntry("cost_state", "RESERVED");
  }

  @Test
  @DisplayName("예산이 모자라면 503이고 요청·메시지·발송 횟수를 남기지 않는다. 예산이 돌아오면 바로 다시 요청할 수 있다")
  void budgetExhausted() throws Exception {
    Client mine = client("issue-budget");
    jdbc.update(
        "INSERT INTO message_budget (provider, business_month, limit_units)"
            + " VALUES ('SOLAPI', DATE_FORMAT(CONVERT_TZ(UTC_TIMESTAMP(), '+00:00', '+09:00'),"
            + " '%Y-%m-01'), 0) ON DUPLICATE KEY UPDATE limit_units = 0");

    mine.request("+821077778888", "0", UUID.randomUUID())
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value("TEMPORARILY_UNAVAILABLE"));
    assertThat(sender.codes).isEmpty();
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM verification_challenge WHERE member_id = ?",
                Long.class,
                mine.member.memberId()))
        .isZero();

    jdbc.update("UPDATE message_budget SET limit_units = 300000");
    mine.request("+821077778888", "0", UUID.randomUUID()).andExpect(status().isOk());
    assertThat(sender.codes).hasSize(1);
  }

  @Test
  @DisplayName("연락처 버전이 다르면 409, 번호 형식이 틀리면 400이고 둘 다 보내지 않는다. 오류 응답과 로그에 번호 원문이 없다")
  void rejectsBadInput(CapturedOutput output) throws Exception {
    Client mine = client("issue-input");

    mine.request("+821099990000", "3", UUID.randomUUID())
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("CONTACT_CHANGED"));
    MvcResult bad =
        mine.request("01099990000", "0", UUID.randomUUID())
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
            .andReturn();
    mine.request("+821099990000", "x", UUID.randomUUID()).andExpect(status().isBadRequest());

    assertThat(sender.codes).isEmpty();
    assertThat(bad.getResponse().getContentAsString()).doesNotContain("99990000");
    assertThat(output.getAll()).doesNotContain("99990000");
  }

  @Test
  @DisplayName("최근 5분 안에 재인증하지 않았으면 403 REAUTH_REQUIRED이고 코드를 보내지 않는다(02 7.2절)")
  void requiresRecentReauthentication() throws Exception {
    long before = challenges();
    client("no-reauth", null)
        .request("+821012341234", "0", UUID.randomUUID())
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("REAUTH_REQUIRED"));
    client("stale-reauth", Instant.now().minus(Duration.ofMinutes(6)))
        .request("+821012341235", "0", UUID.randomUUID())
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("REAUTH_REQUIRED"));
    assertThat(sender.codes).isEmpty();
    assertThat(challenges()).isEqualTo(before);
  }

  @Test
  @DisplayName("로그인하지 않으면 401")
  void requiresMember() throws Exception {
    mvc.perform(
            post("/api/v1/me/notification-verifications")
                .header("X-Command-Token", "t")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType("application/json")
                .content("{\"phoneNumber\":\"+821012341234\",\"expectedContactVersion\":\"0\"}"))
        .andExpect(status().is4xxClientError());
    assertThat(sender.codes).isEmpty();
  }

  private Client client(String providerUserId) throws Exception {
    return client(providerUserId, Instant.now());
  }

  private Client client(String providerUserId, Instant reauthenticatedAt) throws Exception {
    MemberPrincipal member =
        MemberPrincipal.of(
            accounts.login(OAuthProvider.KAKAO, providerUserId), OAuthProvider.KAKAO);
    MvcResult state =
        mvc.perform(get("/api/v1/session").with(oauth2Login().oauth2User(member))).andReturn();
    String csrf = body(state).get("csrfToken").asText();
    String setCookie = state.getResponse().getHeader("Set-Cookie");
    Cookie session =
        new Cookie(
            "__Host-lottery_session",
            setCookie.substring(setCookie.indexOf('=') + 1, setCookie.indexOf(';')));
    if (reauthenticatedAt != null) {
      Session stored =
          sessions.findById(
              new String(Base64.getDecoder().decode(session.getValue()), StandardCharsets.UTF_8));
      stored.setAttribute(
          "com.lottery.lottery_service.auth.Reauthentication.completedAt", reauthenticatedAt);
      saveSession(stored);
    }
    return new Client(member, session, csrf);
  }

  private long challenges() {
    return jdbc.queryForObject("SELECT COUNT(*) FROM verification_challenge", Long.class);
  }

  @SuppressWarnings("unchecked")
  private void saveSession(Session session) {
    sessions.save(session);
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

    ResultActions request(String phone, String version, UUID key) throws Exception {
      return request(phone, version, key, token("REQUEST_VERIFICATION"));
    }

    ResultActions request(String phone, String version, UUID key, String token) throws Exception {
      return mvc.perform(
          post("/api/v1/me/notification-verifications")
              .cookie(session)
              .with(oauth2Login().oauth2User(member))
              .header("X-CSRF-TOKEN", csrf)
              .header("X-Command-Token", token)
              .header("Idempotency-Key", key.toString())
              .contentType("application/json")
              .content(
                  "{\"phoneNumber\":\""
                      + phone
                      + "\",\"expectedContactVersion\":\""
                      + version
                      + "\"}"));
    }

    ResultActions verify(UUID challenge, String code) throws Exception {
      return mvc.perform(
          post("/api/v1/me/notification-verifications/" + challenge + "/verify")
              .cookie(session)
              .with(oauth2Login().oauth2User(member))
              .header("X-CSRF-TOKEN", csrf)
              .header("X-Command-Token", token("VERIFY_CONTACT"))
              .header("Idempotency-Key", UUID.randomUUID().toString())
              .contentType("application/json")
              .content("{\"code\":\"" + code + "\"}"));
    }
  }
}
