package com.lottery.lottery_service.recommendation.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lottery.lottery_service.account.AccountStore;
import com.lottery.lottery_service.account.GuestIssueRateLimiter;
import com.lottery.lottery_service.auth.MemberPrincipal;
import com.lottery.lottery_service.auth.OAuthProvider;
import com.lottery.lottery_service.common.command.CommandOperationStore;
import com.lottery.lottery_service.common.command.CommandStart;
import com.lottery.lottery_service.common.command.CommandTokenCodec;
import com.lottery.lottery_service.round.RoundStore;
import com.lottery.lottery_service.round.domain.RoundSchedule;
import com.lottery.lottery_service.support.MySqlContainerSupport;
import com.lottery.lottery_service.support.OpenApiContract;
import jakarta.servlet.http.Cookie;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 비회원이 자격·토큰을 받아 추천을 받고, 같은 요청을 다시 보내면 같은 결과를 받는지 실제 보안 필터와 MySQL로 확인한다. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(OpenApiContract.Config.class)
class RecommendationWebMySqlTest extends MySqlContainerSupport {

  private static final String DB = "recommendation_web";
  private static final int ROUND = 1300;

  @Autowired private MockMvc mvc;
  @Autowired private RoundStore rounds;
  @Autowired private CommandOperationStore operations;
  @Autowired private CommandTokenCodec codec;
  @Autowired private AccountStore accounts;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private GuestIssueRateLimiter guestLimiter;
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
  @DisplayName(
      "비회원 추천은 5세트와 기록 ID를 주고, 같은 토큰·키·본문을 다시 보내면 같은 결과다. 토큰 없이는 400. 다른 비회원·회원은 그 명령을 404로, 기록을 0건으로 본다")
  void guestRecommendation() throws Exception {
    Instant now = rounds.dbNow();
    rounds.register(
        new RoundSchedule(
            ROUND, now.minus(Duration.ofDays(1)), now.plusSeconds(3600), now.plusSeconds(7200), 1));
    MvcResult issued = mvc.perform(post("/api/v1/guest-credentials")).andReturn();
    Cookie guest = issued.getResponse().getCookie("__Host-lottery_guest");
    String csrf = body(issued).get("csrfToken").asString();
    String command =
        body(mvc.perform(
                    post("/api/v1/command-tokens")
                        .cookie(guest)
                        .header("X-CSRF-TOKEN", csrf)
                        .contentType("application/json")
                        .content(
                            "{\"operation\":\"RECOMMEND\",\"round\":"
                                + ROUND
                                + ",\"scheduleVersion\":\"1\"}"))
                .andExpect(status().isOk())
                .andReturn())
            .get("token")
            .asString();
    String key = UUID.randomUUID().toString();
    String input =
        "{\"round\":" + ROUND + ",\"scheduleVersion\":\"1\",\"include\":[],\"exclude\":[]}";

    MvcResult first =
        mvc.perform(
                post("/api/v1/lotto/recommendations")
                    .cookie(guest)
                    .header("X-CSRF-TOKEN", csrf)
                    .header("X-Command-Token", command)
                    .header("Idempotency-Key", key)
                    .contentType("application/json")
                    .content(input))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("SUCCEEDED"))
            .andExpect(jsonPath("$.data.sets.length()").value(5))
            .andExpect(jsonPath("$.data.generationMode").value("BASIC"))
            .andExpect(jsonPath("$.data.sets[0].recordId").isString())
            .andReturn();
    MvcResult again =
        mvc.perform(
                post("/api/v1/lotto/recommendations")
                    .cookie(guest)
                    .header("X-CSRF-TOKEN", csrf)
                    .header("X-Command-Token", command)
                    .header("Idempotency-Key", key)
                    .contentType("application/json")
                    .content(input))
            .andExpect(status().isOk())
            .andReturn();
    assertThat(body(again).get("data")).isEqualTo(body(first).get("data"));
    assertThat(body(again).get("operationId")).isEqualTo(body(first).get("operationId"));

    String operationId = body(first).get("operationId").asString();
    MvcResult found =
        mvc.perform(get("/api/v1/operations/" + operationId).cookie(guest))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("SUCCEEDED"))
            .andReturn();
    assertThat(body(found).get("data")).isEqualTo(body(first).get("data"));
    Cookie otherGuest =
        mvc.perform(post("/api/v1/guest-credentials"))
            .andReturn()
            .getResponse()
            .getCookie("__Host-lottery_guest");
    mvc.perform(get("/api/v1/operations/" + operationId).cookie(otherGuest))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    MemberPrincipal stranger =
        MemberPrincipal.of(
            accounts.login(OAuthProvider.KAKAO, "rec-op-stranger"), OAuthProvider.KAKAO);
    mvc.perform(get("/api/v1/operations/" + operationId).with(oauth2Login().oauth2User(stranger)))
        .andExpect(status().isNotFound());
    mvc.perform(
            get("/api/v1/guest/lotto/records")
                .cookie(otherGuest)
                .param("round", Integer.toString(ROUND)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(0));

    long guestSubject =
        jdbc.queryForObject(
            "SELECT owner_subject_id FROM lotto_record WHERE public_id = UUID_TO_BIN(?)",
            Long.class,
            body(first).get("data").get("sets").get(0).get("recordId").asString());
    UUID guestPublicId =
        jdbc.queryForObject(
            "SELECT BIN_TO_UUID(public_id) FROM actor_subject WHERE id = ?",
            UUID.class,
            guestSubject);
    CommandStart started =
        operations.begin(
            guestSubject,
            codec.verify(
                codec.issue(guestPublicId, "RECOMMEND", ROUND, 1L), guestPublicId, "RECOMMEND"),
            UUID.randomUUID(),
            new byte[32]);
    mvc.perform(
            get("/api/v1/operations/" + ((CommandStart.Started) started).publicId()).cookie(guest))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("PROCESSING"))
        .andExpect(jsonPath("$.data.pollUrl").isString());

    mvc.perform(
            get("/api/v1/guest/lotto/records")
                .cookie(guest)
                .param("round", Integer.toString(ROUND)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(5))
        .andExpect(jsonPath("$.items[0].recommended").value(true));
    MvcResult other = mvc.perform(post("/api/v1/guest-credentials")).andReturn();
    mvc.perform(
            get("/api/v1/guest/lotto/records")
                .cookie(other.getResponse().getCookie("__Host-lottery_guest"))
                .param("round", Integer.toString(ROUND)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(0));
    mvc.perform(get("/api/v1/guest/lotto/records").param("round", Integer.toString(ROUND)))
        .andExpect(status().isUnauthorized());

    mvc.perform(
            post("/api/v1/lotto/recommendations")
                .cookie(guest)
                .header("X-CSRF-TOKEN", csrf)
                .header("Idempotency-Key", key)
                .contentType("application/json")
                .content(input))
        .andExpect(status().isBadRequest());
  }

  @Test
  @DisplayName("회원 조건 추천은 CONDITION이고, 토큰과 다른 회차는 FORBIDDEN, 넣을·뺄 번호가 겹치면 422 CONDITION_INVALID")
  void memberConditionAndFailures() throws Exception {
    Instant now = rounds.dbNow();
    if (jdbc.queryForObject(
            "SELECT COUNT(*) FROM lotto_round WHERE round_no = ?", Long.class, ROUND + 1)
        == 0) {
      rounds.register(
          new RoundSchedule(
              ROUND + 1,
              now.minus(Duration.ofDays(1)),
              now.plusSeconds(3600),
              now.plusSeconds(7200),
              1));
    }
    int round = ROUND + 1;
    MemberPrincipal member =
        MemberPrincipal.of(
            accounts.login(OAuthProvider.GOOGLE, "rec-member"), OAuthProvider.GOOGLE);
    MvcResult state =
        mvc.perform(get("/api/v1/session").with(oauth2Login().oauth2User(member))).andReturn();
    String csrf = body(state).get("csrfToken").asString();
    String setCookie = state.getResponse().getHeader("Set-Cookie");
    Cookie session =
        new Cookie(
            "__Host-lottery_session",
            setCookie.substring(setCookie.indexOf('=') + 1, setCookie.indexOf(';')));
    String command =
        body(mvc.perform(
                    post("/api/v1/command-tokens")
                        .cookie(session)
                        .with(oauth2Login().oauth2User(member))
                        .header("X-CSRF-TOKEN", csrf)
                        .contentType("application/json")
                        .content(
                            "{\"operation\":\"RECOMMEND\",\"round\":"
                                + round
                                + ",\"scheduleVersion\":\"1\"}"))
                .andReturn())
            .get("token")
            .asString();

    mvc.perform(
            recommendation(session, member, csrf, command)
                .content(
                    "{\"round\":"
                        + round
                        + ",\"scheduleVersion\":\"1\",\"include\":[1,2],\"exclude\":[44,45]}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.generationMode").value("CONDITION"))
        .andExpect(jsonPath("$.data.filterVersion").doesNotExist());
    mvc.perform(
            recommendation(session, member, csrf, command)
                .content(
                    "{\"round\":"
                        + ROUND
                        + ",\"scheduleVersion\":\"1\",\"include\":[],\"exclude\":[]}"))
        .andExpect(status().isForbidden());
    mvc.perform(
            recommendation(session, member, csrf, command)
                .content(
                    "{\"round\":"
                        + round
                        + ",\"scheduleVersion\":\"1\",\"include\":[3],\"exclude\":[3]}"))
        .andExpect(status().isUnprocessableContent())
        .andExpect(jsonPath("$.code").value("CONDITION_INVALID"));
  }

  @Test
  @DisplayName("한 IP의 자격 발급이 한도를 넘어 429와 Retry-After를 받아도, 그 IP에서 먼저 받은 자격으로는 추천할 수 있다(T40)")
  void issueLimitDoesNotBlockExistingGuest() throws Exception {
    int round = ROUND + 2;
    Instant now = rounds.dbNow();
    rounds.register(
        new RoundSchedule(
            round, now.minus(Duration.ofDays(1)), now.plusSeconds(3600), now.plusSeconds(7200), 1));
    String ip = "10.40.0.1";
    MvcResult issued =
        mvc.perform(post("/api/v1/guest-credentials").with(remote(ip)))
            .andExpect(status().isCreated())
            .andReturn();
    Cookie guest = issued.getResponse().getCookie("__Host-lottery_guest");
    String csrf = body(issued).get("csrfToken").asString();
    for (int i = 1; i < GuestIssueRateLimiter.LIMIT; i++) {
      assertThat(guestLimiter.tryAcquire(ip)).isEmpty();
    }

    mvc.perform(post("/api/v1/guest-credentials").with(remote(ip)))
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
        .andExpect(header().exists("Retry-After"));
    String command =
        body(mvc.perform(
                    post("/api/v1/command-tokens")
                        .with(remote(ip))
                        .cookie(guest)
                        .header("X-CSRF-TOKEN", csrf)
                        .contentType("application/json")
                        .content(
                            "{\"operation\":\"RECOMMEND\",\"round\":"
                                + round
                                + ",\"scheduleVersion\":\"1\"}"))
                .andExpect(status().isOk())
                .andReturn())
            .get("token")
            .asString();
    mvc.perform(
            post("/api/v1/lotto/recommendations")
                .with(remote(ip))
                .cookie(guest)
                .header("X-CSRF-TOKEN", csrf)
                .header("X-Command-Token", command)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType("application/json")
                .content(
                    "{\"round\":"
                        + round
                        + ",\"scheduleVersion\":\"1\",\"include\":[],\"exclude\":[]}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.sets.length()").value(5));
  }

  @Test
  @DisplayName(
      "같은 키 재전송은 같은 5세트, 같은 키에 다른 본문은 409 IDEMPOTENCY_CONFLICT, 토큰 뒤 일정이 바뀌면 409 ROUND_CHANGED이고 응답은 모두 계약 형식이다")
  void memberRetryConflictAndScheduleChange() throws Exception {
    Instant now = rounds.dbNow();
    int round = ROUND + 3;
    rounds.register(
        new RoundSchedule(
            round, now.minus(Duration.ofDays(1)), now.plusSeconds(3600), now.plusSeconds(7200), 1));
    MemberPrincipal member =
        MemberPrincipal.of(
            accounts.login(OAuthProvider.GOOGLE, "rec-member-conflict"), OAuthProvider.GOOGLE);
    MvcResult state =
        mvc.perform(get("/api/v1/session").with(oauth2Login().oauth2User(member))).andReturn();
    String csrf = body(state).get("csrfToken").asString();
    String setCookie = state.getResponse().getHeader("Set-Cookie");
    Cookie session =
        new Cookie(
            "__Host-lottery_session",
            setCookie.substring(setCookie.indexOf('=') + 1, setCookie.indexOf(';')));
    String command = recommendToken(session, member, csrf, round);
    String key = UUID.randomUUID().toString();
    String basic =
        "{\"round\":" + round + ",\"scheduleVersion\":\"1\",\"include\":[],\"exclude\":[]}";

    JsonNode first =
        body(
            mvc.perform(recommendation(session, member, csrf, command, key).content(basic))
                .andExpect(status().isOk())
                .andReturn());
    JsonNode again =
        body(
            mvc.perform(recommendation(session, member, csrf, command, key).content(basic))
                .andExpect(status().isOk())
                .andReturn());
    assertThat(again.get("operationId")).isEqualTo(first.get("operationId"));
    assertThat(again.get("data").get("sets")).isEqualTo(first.get("data").get("sets"));
    mvc.perform(
            recommendation(session, member, csrf, command, key)
                .content(
                    "{\"round\":"
                        + round
                        + ",\"scheduleVersion\":\"1\",\"include\":[7],\"exclude\":[]}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));

    MemberPrincipal other =
        MemberPrincipal.of(
            accounts.login(OAuthProvider.GOOGLE, "rec-member-schedule"), OAuthProvider.GOOGLE);
    MvcResult otherState =
        mvc.perform(get("/api/v1/session").with(oauth2Login().oauth2User(other))).andReturn();
    String otherCsrf = body(otherState).get("csrfToken").asString();
    String otherCookie = otherState.getResponse().getHeader("Set-Cookie");
    Cookie otherSession =
        new Cookie(
            "__Host-lottery_session",
            otherCookie.substring(otherCookie.indexOf('=') + 1, otherCookie.indexOf(';')));
    String next = recommendToken(otherSession, other, otherCsrf, round);
    jdbc.update("UPDATE lotto_round SET schedule_version = 2 WHERE round_no = ?", round);
    mvc.perform(
            recommendation(otherSession, other, otherCsrf, next, UUID.randomUUID().toString())
                .content(basic))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("ROUND_CHANGED"))
        .andExpect(jsonPath("$.retryable").value(false));
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM recommendation_request WHERE round_no = ?",
                Long.class,
                round))
        .isEqualTo(1);
  }

  private String recommendToken(Cookie session, MemberPrincipal member, String csrf, int round)
      throws Exception {
    return body(mvc.perform(
                post("/api/v1/command-tokens")
                    .cookie(session)
                    .with(oauth2Login().oauth2User(member))
                    .header("X-CSRF-TOKEN", csrf)
                    .contentType("application/json")
                    .content(
                        "{\"operation\":\"RECOMMEND\",\"round\":"
                            + round
                            + ",\"scheduleVersion\":\"1\"}"))
            .andExpect(status().isOk())
            .andReturn())
        .get("token")
        .asString();
  }

  private static org.springframework.test.web.servlet.request.RequestPostProcessor remote(
      String ip) {
    return request -> {
      request.setRemoteAddr(ip);
      return request;
    };
  }

  private MockHttpServletRequestBuilder recommendation(
      Cookie session, MemberPrincipal member, String csrf, String command) {
    return recommendation(session, member, csrf, command, UUID.randomUUID().toString());
  }

  private MockHttpServletRequestBuilder recommendation(
      Cookie session, MemberPrincipal member, String csrf, String command, String key) {
    return post("/api/v1/lotto/recommendations")
        .cookie(session)
        .with(oauth2Login().oauth2User(member))
        .header("X-CSRF-TOKEN", csrf)
        .header("X-Command-Token", command)
        .header("Idempotency-Key", key)
        .contentType("application/json");
  }

  private JsonNode body(MvcResult result) throws Exception {
    return json.readTree(result.getResponse().getContentAsString());
  }
}
