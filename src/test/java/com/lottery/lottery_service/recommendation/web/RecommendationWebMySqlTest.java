package com.lottery.lottery_service.recommendation.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lottery.lottery_service.account.AccountStore;
import com.lottery.lottery_service.auth.MemberPrincipal;
import com.lottery.lottery_service.auth.OAuthProvider;
import com.lottery.lottery_service.common.command.CommandOperationStore;
import com.lottery.lottery_service.common.command.CommandStart;
import com.lottery.lottery_service.common.command.CommandTokenCodec;
import com.lottery.lottery_service.round.RoundStore;
import com.lottery.lottery_service.round.domain.RoundSchedule;
import com.lottery.lottery_service.support.MySqlContainerSupport;
import jakarta.servlet.http.Cookie;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** 비회원이 자격·토큰을 받아 추천을 받고, 같은 요청을 다시 보내면 같은 결과를 받는지 실제 보안 필터와 MySQL로 확인한다. */
@SpringBootTest
@AutoConfigureMockMvc
class RecommendationWebMySqlTest extends MySqlContainerSupport {

  private static final String DB = "recommendation_web";
  private static final int ROUND = 1300;

  @Autowired private MockMvc mvc;
  @Autowired private RoundStore rounds;
  @Autowired private CommandOperationStore operations;
  @Autowired private CommandTokenCodec codec;
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
    registry.add("server.servlet.session.cookie.name", () -> "__Host-lottery_session");
  }

  @Test
  @DisplayName("비회원 추천은 5세트와 기록 ID를 주고, 같은 토큰·키·본문을 다시 보내면 같은 결과다. 토큰 없이는 400")
  void guestRecommendation() throws Exception {
    Instant now = rounds.dbNow();
    rounds.register(
        new RoundSchedule(
            ROUND, now.minus(Duration.ofDays(1)), now.plusSeconds(3600), now.plusSeconds(7200), 1));
    MvcResult issued = mvc.perform(post("/api/v1/guest-credentials")).andReturn();
    Cookie guest = issued.getResponse().getCookie("__Host-lottery_guest");
    String csrf = body(issued).get("csrfToken").asText();
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
            .asText();
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

    String operationId = body(first).get("operationId").asText();
    MvcResult found =
        mvc.perform(get("/api/v1/operations/" + operationId).cookie(guest))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("SUCCEEDED"))
            .andReturn();
    assertThat(body(found).get("data")).isEqualTo(body(first).get("data"));

    long guestSubject =
        jdbc.queryForObject(
            "SELECT owner_subject_id FROM lotto_record WHERE public_id = UUID_TO_BIN(?)",
            Long.class,
            body(first).get("data").get("sets").get(0).get("recordId").asText());
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
        .andExpect(status().isAccepted())
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
    String csrf = body(state).get("csrfToken").asText();
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
            .asText();

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
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("CONDITION_INVALID"));
  }

  private MockHttpServletRequestBuilder recommendation(
      Cookie session, MemberPrincipal member, String csrf, String command) {
    return post("/api/v1/lotto/recommendations")
        .cookie(session)
        .with(oauth2Login().oauth2User(member))
        .header("X-CSRF-TOKEN", csrf)
        .header("X-Command-Token", command)
        .header("Idempotency-Key", UUID.randomUUID().toString())
        .contentType("application/json");
  }

  private JsonNode body(MvcResult result) throws Exception {
    return json.readTree(result.getResponse().getContentAsString());
  }
}
