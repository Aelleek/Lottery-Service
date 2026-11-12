package com.lottery.lottery_service.admin.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lottery.lottery_service.account.AccountStore;
import com.lottery.lottery_service.admin.mfa.AdminMfaStore;
import com.lottery.lottery_service.admin.mfa.Totp;
import com.lottery.lottery_service.auth.MemberPrincipal;
import com.lottery.lottery_service.auth.OAuthProvider;
import com.lottery.lottery_service.common.number.LottoNumber;
import com.lottery.lottery_service.common.number.LottoNumbers;
import com.lottery.lottery_service.result.domain.WinningNumbers;
import com.lottery.lottery_service.result.publish.ResultPublisher;
import com.lottery.lottery_service.result.source.OfficialDraw;
import com.lottery.lottery_service.support.MySqlContainerSupport;
import com.lottery.lottery_service.support.OpenApiContract;
import jakarta.servlet.http.Cookie;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
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

/** 관리자 변경 API(다시 보내기, 승인, 직접 등록)의 요청 키 재전송·재인증·감사를 실제 보안 필터와 MySQL로 확인한다. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(OpenApiContract.Config.class)
class AdminChangeWebMySqlTest extends MySqlContainerSupport {

  private static final String DB = "admin_change_web";
  private static final AtomicInteger ROUNDS = new AtomicInteger(1300);
  private static final String NUMBERS = "[3,11,19,27,36,44]";

  @Autowired private MockMvc mvc;
  @Autowired private AccountStore accounts;
  @Autowired private AdminMfaStore mfa;
  @Autowired private ResultPublisher publisher;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private ObjectMapper json;
  @Autowired private SessionRepository<? extends Session> sessions;

  private final int round = ROUNDS.incrementAndGet();
  private MemberPrincipal admin;
  private Cookie session;
  private String csrf;

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
  void openAdminSession() throws Exception {
    jdbc.update(
        "INSERT INTO lotto_round (round_no, sales_open_at, sales_close_at, draw_at, schedule_version)"
            + " VALUES (?, NOW(6) - INTERVAL 7 DAY, NOW(6) - INTERVAL 2 HOUR,"
            + " NOW(6) - INTERVAL 1 HOUR, 1)",
        round);
    admin =
        MemberPrincipal.of(
            accounts.login(OAuthProvider.GOOGLE, "admin-" + System.nanoTime()),
            OAuthProvider.GOOGLE);
    jdbc.update(
        "INSERT INTO admin_grant (member_id, role, granted_at) VALUES (?, 'ADMIN', NOW(6))",
        admin.memberId());
    byte[] secret = decode(mfa.enroll(admin.memberId()).secret());
    long step = Totp.step(Instant.now());
    mfa.confirm(admin.memberId(), Totp.code(secret, step - 1));
    MvcResult state =
        mvc.perform(get("/api/v1/session").with(oauth2Login().oauth2User(admin))).andReturn();
    session = cookie(state.getResponse().getHeader("Set-Cookie"));
    csrf = body(state).get("csrfToken").asText();
    MvcResult started =
        mvc.perform(
                as(post("/api/v1/admin/session"))
                    .contentType("application/json")
                    .content("{\"code\":\"" + Totp.code(secret, step) + "\"}"))
            .andExpect(status().isOk())
            .andReturn();
    session = cookie(started.getResponse().getHeader("Set-Cookie"));
    csrf = body(started).get("csrfToken").asText();
  }

  @Test
  @DisplayName("직접 등록은 재인증 없이 403이고 같은 키로 재인증 뒤 201, 같은 키 재전송은 같은 응답, 다른 본문은 409")
  void registerManualResult() throws Exception {
    String key = UUID.randomUUID().toString();
    String body =
        "{\"numbers\":"
            + NUMBERS
            + ",\"bonus\":8,\"expectedPublicationVersion\":\"0\",\"reason\":\"공식 발표 확인\"}";

    mvc.perform(register(key, body))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("REAUTH_REQUIRED"));
    reauthenticate();
    mvc.perform(register(key, body))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.state").value("PUBLISHED"))
        .andExpect(jsonPath("$.source").value("MANUAL"))
        .andExpect(jsonPath("$.apiConfirmedAt").value(nullValue()));
    mvc.perform(register(key, body))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.publishedRevision").value("1"));
    mvc.perform(register(key, body.replace("\"bonus\":8", "\"bonus\":9")))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
    mvc.perform(register(UUID.randomUUID().toString(), body))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("RESULT_ALREADY_PUBLISHED"));
    mvc.perform(register(UUID.randomUUID().toString(), body.replace("\"bonus\":8", "\"bonus\":44")))
        .andExpect(status().isUnprocessableEntity());
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM admin_audit_event WHERE action = 'RESULT_MANUAL_REGISTER'"
                    + " AND outcome = 'SUCCEEDED'",
                Long.class))
        .isEqualTo(1);
  }

  @Test
  @DisplayName("격리 결과 승인은 새 게시본을 주고, 같은 키 재전송은 같은 결과, 버전이 다르면 409이고 그 키는 다시 보내도 같은 실패")
  void publishQuarantined() throws Exception {
    publisher.receive(draw(LottoNumbers.of(1, 2, 3, 4, 5, 6), 7));
    publisher.receive(draw(LottoNumbers.of(1, 2, 3, 4, 5, 9), 7));
    String quarantined =
        jdbc.queryForObject(
            "SELECT BIN_TO_UUID(public_id) FROM draw_result WHERE round_no = ? AND state = 'QUARANTINED'",
            String.class,
            round);
    String key = UUID.randomUUID().toString();
    String body = "{\"expectedPublicationVersion\":\"1\",\"reason\":\"동행복권 공지 확인\"}";

    mvc.perform(publish(quarantined, key, body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.publishedRevision").value("2"))
        .andExpect(jsonPath("$.numbers[5]").value(9));
    mvc.perform(publish(quarantined, key, body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.publishedRevision").value("2"));

    publisher.receive(draw(LottoNumbers.of(1, 2, 3, 4, 5, 10), 7));
    String next =
        jdbc.queryForObject(
            "SELECT BIN_TO_UUID(public_id) FROM draw_result WHERE round_no = ?"
                + " AND state = 'QUARANTINED'",
            String.class,
            round);
    String stale = UUID.randomUUID().toString();
    mvc.perform(publish(next, stale, body))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
    mvc.perform(publish(next, stale, body))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
    mvc.perform(publish(quarantined, UUID.randomUUID().toString(), body))
        .andExpect(status().isNotFound());
    mvc.perform(publish(UUID.randomUUID().toString(), UUID.randomUUID().toString(), body))
        .andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("수동 다시 보내기는 202와 실행 ID를 주고, 같은 키 재전송은 같은 실행 ID이며 다시 실행하지 않는다")
  void dispatch() throws Exception {
    publisher.receive(draw(LottoNumbers.of(1, 2, 3, 4, 5, 6), 7));
    String key = UUID.randomUUID().toString();

    JsonNode first =
        body(
            mvc.perform(dispatchRequest(key, "{\"reason\":\"제공자 장애 복구\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.round").value(round))
                .andReturn());
    JsonNode again =
        body(
            mvc.perform(dispatchRequest(key, "{\"reason\":\"제공자 장애 복구\"}"))
                .andExpect(status().isAccepted())
                .andReturn());

    assertThat(again.get("dispatchId").asText()).isEqualTo(first.get("dispatchId").asText());
    assertThat(again.get("acceptedAt").asText()).isEqualTo(first.get("acceptedAt").asText());
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM admin_audit_event WHERE action = 'NOTIFICATION_RESEND'",
                Long.class))
        .isEqualTo(1);
    mvc.perform(dispatchRequest(UUID.randomUUID().toString(), "{\"reason\":\" \"}"))
        .andExpect(status().isBadRequest());
  }

  private MockHttpServletRequestBuilder register(String key, String body) {
    return as(post("/api/v1/admin/rounds/" + round + "/draw-results"))
        .header("Idempotency-Key", key)
        .contentType("application/json")
        .content(body);
  }

  private MockHttpServletRequestBuilder publish(String resultId, String key, String body) {
    return as(post("/api/v1/admin/draw-results/" + resultId + "/publication"))
        .header("Idempotency-Key", key)
        .contentType("application/json")
        .content(body);
  }

  private MockHttpServletRequestBuilder dispatchRequest(String key, String body) {
    return as(post("/api/v1/admin/rounds/" + round + "/notification-dispatches"))
        .header("Idempotency-Key", key)
        .contentType("application/json")
        .content(body);
  }

  private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request) {
    MockHttpServletRequestBuilder r = request.with(oauth2Login().oauth2User(admin));
    if (session != null) {
      r = r.cookie(session);
    }
    return csrf == null ? r : r.header("X-CSRF-TOKEN", csrf);
  }

  @SuppressWarnings("unchecked")
  private void reauthenticate() {
    String id = new String(Base64.getDecoder().decode(session.getValue()), StandardCharsets.UTF_8);
    SessionRepository<Session> repository = (SessionRepository<Session>) sessions;
    Session stored = repository.findById(id);
    stored.setAttribute(
        "com.lottery.lottery_service.auth.Reauthentication.completedAt", Instant.now());
    repository.save(stored);
  }

  private OfficialDraw draw(LottoNumbers numbers, int bonus) {
    return new OfficialDraw(
        round, new WinningNumbers(numbers, LottoNumber.of(bonus)), null, null, new byte[32]);
  }

  private JsonNode body(MvcResult result) throws Exception {
    return json.readTree(result.getResponse().getContentAsString());
  }

  private static Cookie cookie(String setCookie) {
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
}
