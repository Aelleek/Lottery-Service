package com.lottery.lottery_service.acceptance;

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
import com.lottery.lottery_service.common.number.LottoNumbers;
import com.lottery.lottery_service.common.web.SecuritySignals;
import com.lottery.lottery_service.round.RoundStore;
import com.lottery.lottery_service.round.domain.RoundSchedule;
import com.lottery.lottery_service.support.MySqlContainerSupport;
import jakarta.servlet.http.Cookie;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** 보안 수용 시나리오(08 5절 T58~T61)를 실제 보안 필터와 MySQL로 확인한다. */
@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class SecurityAcceptanceWebMySqlTest extends MySqlContainerSupport {

  private static final String DB = "security_acceptance";
  private static final int ROUND = 1400;

  @Autowired private MockMvc mvc;
  @Autowired private AccountStore accounts;
  @Autowired private RoundStore rounds;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private ObjectMapper json;
  @Autowired private SecuritySignals signals;

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
  @DisplayName("T58: 회원 A의 세션으로 B의 기록을 취소하면 404이고 B의 기록은 그대로다")
  void otherMembersRecordIsNotFound() throws Exception {
    registerRound();
    MemberPrincipal a = member("t58-a");
    MemberPrincipal b = member("t58-b");
    String recordOfB = UUID.randomUUID().toString();
    jdbc.update(
        "INSERT INTO lotto_record (public_id, owner_subject_id, round_no, numbers_key, purchased,"
            + " purchased_at) VALUES (UUID_TO_BIN(?), ?, ?, ?, TRUE, NOW(6))",
        recordOfB,
        b.subjectId(),
        ROUND,
        LottoNumbers.of(1, 2, 3, 4, 5, 6).key());
    Session session = session(a);
    String token = commandToken(session, a, "CANCEL_PURCHASE");

    mvc.perform(
            post("/api/v1/me/lotto/records/" + recordOfB + "/cancel-purchase")
                .cookie(session.cookie())
                .with(oauth2Login().oauth2User(a))
                .header("X-CSRF-TOKEN", session.csrf())
                .header("X-Command-Token", token)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType("application/json")
                .content("{\"expectedVersion\":\"0\",\"memberId\":" + b.memberId() + "}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    assertThat(
            jdbc.queryForObject(
                "SELECT purchased FROM lotto_record WHERE public_id = UUID_TO_BIN(?)",
                Boolean.class,
                recordOfB))
        .isTrue();
  }

  @Test
  @DisplayName("T59: 토큰 없음, 다른 비회원 자격의 토큰, 다른 회원 세션의 토큰은 모두 403 CSRF_REJECTED이고 보안 신호로 센다")
  void foreignCsrfTokensRejected() throws Exception {
    MvcResult g1 = mvc.perform(post("/api/v1/guest-credentials")).andReturn();
    MvcResult g2 = mvc.perform(post("/api/v1/guest-credentials")).andReturn();
    Cookie guest1 = g1.getResponse().getCookie("__Host-lottery_guest");
    String tokenOfGuest2 = body(g2).get("csrfToken").asText();
    MemberPrincipal a = member("t59-a");
    MemberPrincipal b = member("t59-b");
    Session sessionA = session(a);
    Session sessionB = session(b);

    for (String token : new String[] {null, tokenOfGuest2, sessionB.csrf()}) {
      var request = post("/api/v1/command-tokens").cookie(guest1).contentType("application/json");
      if (token != null) {
        request.header("X-CSRF-TOKEN", token);
      }
      mvc.perform(
              request.content(
                  "{\"operation\":\"RECOMMEND\",\"round\":1,\"scheduleVersion\":\"1\"}"))
          .andExpect(status().isForbidden())
          .andExpect(jsonPath("$.code").value("CSRF_REJECTED"));
    }
    mvc.perform(
            post("/api/v1/command-tokens")
                .cookie(sessionA.cookie())
                .with(oauth2Login().oauth2User(a))
                .header("X-CSRF-TOKEN", sessionB.csrf())
                .contentType("application/json")
                .content("{\"operation\":\"WITHDRAW\"}"))
        .andExpect(jsonPath("$.code").value("CSRF_REJECTED"));
    assertThat(signals.drain().get(SecuritySignals.Kind.CSRF_REJECTED).topPath())
        .isEqualTo("/api/v1/command-tokens");
  }

  @Test
  @DisplayName("T60: H2 콘솔은 열리지 않고 응답에 보안 헤더가 있다")
  void productionSurface() throws Exception {
    MvcResult console = mvc.perform(get("/h2-console")).andReturn();
    assertThat(console.getResponse().getStatus()).isIn(401, 403, 404);
    mvc.perform(get("/api/v1/session"))
        .andExpect(header().exists("Content-Security-Policy"))
        .andExpect(header().string("X-Frame-Options", "DENY"))
        .andExpect(header().string("X-Content-Type-Options", "nosniff"));
  }

  @Test
  @DisplayName("T60: HTTPS 요청에는 03 6.2의 헤더 값이 모두 있고, 개인 자료 응답은 저장하지 않게 한다")
  void productionHeaderValues() throws Exception {
    MemberPrincipal member = member("t60-headers");
    mvc.perform(get("/api/v1/session").secure(true))
        .andExpect(
            header()
                .string(
                    "Content-Security-Policy",
                    "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:;"
                        + " connect-src 'self'; object-src 'none'; base-uri 'self';"
                        + " frame-ancestors 'none'"))
        .andExpect(
            header().string("Strict-Transport-Security", "max-age=31536000 ; includeSubDomains"))
        .andExpect(header().string("Referrer-Policy", "strict-origin-when-cross-origin"))
        .andExpect(
            header().string("Permissions-Policy", "camera=(self), microphone=(), geolocation=()"));
    MvcResult records =
        mvc.perform(
                get("/api/v1/me/lotto/records").secure(true).with(oauth2Login().oauth2User(member)))
            .andExpect(status().isOk())
            .andReturn();
    assertThat(records.getResponse().getHeader("Cache-Control")).contains("no-store");
    assertThat(records.getResponse().getHeader("Access-Control-Allow-Origin")).isNull();
  }

  @Test
  @DisplayName("T61: 같은 IP의 회차 결과 조회 121번째는 429와 Retry-After, X-Forwarded-For를 바꿔도 같고 다른 IP는 통과")
  void roundResultRateLimit() throws Exception {
    registerRound();
    for (int i = 0; i < 120; i++) {
      mvc.perform(
              get("/api/v1/lotto/rounds/" + ROUND + "/result")
                  .with(r -> remote(r, "198.51.100.7"))
                  .header("X-Forwarded-For", "203.0.113." + i))
          .andExpect(status().is2xxSuccessful());
    }
    mvc.perform(
            get("/api/v1/lotto/rounds/" + ROUND + "/result").with(r -> remote(r, "198.51.100.7")))
        .andExpect(status().isTooManyRequests())
        .andExpect(header().exists("Retry-After"))
        .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
    mvc.perform(
            get("/api/v1/lotto/rounds/" + ROUND + "/result").with(r -> remote(r, "198.51.100.8")))
        .andExpect(status().is2xxSuccessful());
  }

  @Test
  @DisplayName("T65: 번호가 틀린 요청의 오류 응답과 로그에 보낸 번호 원문이 없다")
  void noRawNumbersInErrors(CapturedOutput output) throws Exception {
    registerRound();
    MvcResult result =
        mvc.perform(
                post("/api/v1/lotto/check")
                    .contentType("application/json")
                    .content("{\"round\":" + ROUND + ",\"sets\":[[13,13,27,38,41,44]]}"))
            .andExpect(status().is4xxClientError())
            .andReturn();
    String body = result.getResponse().getContentAsString();
    assertThat(body).doesNotContain("13,13").doesNotContain("27, 38").doesNotContain("38,41");
    assertThat(output.getAll()).doesNotContain("13,13,27").doesNotContain("13, 13, 27");
  }

  @Test
  @DisplayName("T65: 인증 코드 확인이 형식 오류·없는 요청·깨진 JSON으로 끝나도 오류 응답과 로그에 코드와 전화번호 원문이 없다")
  void noRawCodesOrPhonesInErrors(CapturedOutput output) throws Exception {
    MemberPrincipal member = member("t65-code");
    Session session = session(member);
    String path = "/api/v1/me/notification-verifications/" + UUID.randomUUID() + "/verify";
    List<String> bodies =
        List.of(
            "{\"code\":\"58219x\"}",
            "{\"code\":\"582197\"}",
            "{\"code\":\"+821058219734\",",
            "{\"code\":582197,\"phoneNumber\":\"+821058219734\"}");
    for (String content : bodies) {
      MvcResult result =
          mvc.perform(
                  post(path)
                      .cookie(session.cookie())
                      .with(oauth2Login().oauth2User(member))
                      .header("X-CSRF-TOKEN", session.csrf())
                      .header("X-Command-Token", commandToken(session, member, "VERIFY_CONTACT"))
                      .header("Idempotency-Key", UUID.randomUUID().toString())
                      .contentType("application/json")
                      .content(content))
              .andExpect(status().is4xxClientError())
              .andReturn();
      assertThat(result.getResponse().getContentAsString())
          .doesNotContain("58219")
          .doesNotContain("1058219734");
    }
    assertThat(output.getAll()).doesNotContain("58219").doesNotContain("1058219734");
  }

  private static org.springframework.mock.web.MockHttpServletRequest remote(
      org.springframework.mock.web.MockHttpServletRequest request, String ip) {
    request.setRemoteAddr(ip);
    return request;
  }

  private void registerRound() {
    if (jdbc.queryForObject(
            "SELECT COUNT(*) FROM lotto_round WHERE round_no = ?", Long.class, ROUND)
        > 0) {
      return;
    }
    Instant now = rounds.dbNow();
    rounds.register(
        new RoundSchedule(
            ROUND, now.minus(Duration.ofDays(1)), now.plusSeconds(3600), now.plusSeconds(7200), 1));
  }

  private MemberPrincipal member(String id) {
    return MemberPrincipal.of(accounts.login(OAuthProvider.KAKAO, id), OAuthProvider.KAKAO);
  }

  private Session session(MemberPrincipal member) throws Exception {
    MvcResult state =
        mvc.perform(get("/api/v1/session").with(oauth2Login().oauth2User(member))).andReturn();
    String setCookie = state.getResponse().getHeader("Set-Cookie");
    return new Session(
        new Cookie(
            "__Host-lottery_session",
            setCookie.substring(setCookie.indexOf('=') + 1, setCookie.indexOf(';'))),
        body(state).get("csrfToken").asText());
  }

  private String commandToken(Session session, MemberPrincipal member, String operation)
      throws Exception {
    return body(mvc.perform(
                post("/api/v1/command-tokens")
                    .cookie(session.cookie())
                    .with(oauth2Login().oauth2User(member))
                    .header("X-CSRF-TOKEN", session.csrf())
                    .contentType("application/json")
                    .content("{\"operation\":\"" + operation + "\"}"))
            .andExpect(status().isOk())
            .andReturn())
        .get("token")
        .asText();
  }

  private JsonNode body(MvcResult result) throws Exception {
    return json.readTree(result.getResponse().getContentAsString());
  }

  private record Session(Cookie cookie, String csrf) {}
}
