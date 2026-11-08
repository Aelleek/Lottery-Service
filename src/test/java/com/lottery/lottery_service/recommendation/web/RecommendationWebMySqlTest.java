package com.lottery.lottery_service.recommendation.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** 비회원이 자격·토큰을 받아 추천을 받고, 같은 요청을 다시 보내면 같은 결과를 받는지 실제 보안 필터와 MySQL로 확인한다. */
@SpringBootTest
@AutoConfigureMockMvc
class RecommendationWebMySqlTest extends MySqlContainerSupport {

  private static final String DB = "recommendation_web";
  private static final int ROUND = 1300;

  @Autowired private MockMvc mvc;
  @Autowired private RoundStore rounds;
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

    mvc.perform(
            post("/api/v1/lotto/recommendations")
                .cookie(guest)
                .header("X-CSRF-TOKEN", csrf)
                .header("Idempotency-Key", key)
                .contentType("application/json")
                .content(input))
        .andExpect(status().isBadRequest());
  }

  private JsonNode body(MvcResult result) throws Exception {
    return json.readTree(result.getResponse().getContentAsString());
  }
}
