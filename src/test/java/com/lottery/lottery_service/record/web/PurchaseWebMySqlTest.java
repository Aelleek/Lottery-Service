package com.lottery.lottery_service.record.web;

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

/** 회원 구매 등록 API와 명령 조회를 실제 보안 필터와 MySQL로 확인한다. */
@SpringBootTest
@AutoConfigureMockMvc
class PurchaseWebMySqlTest extends MySqlContainerSupport {

  private static final String DB = "purchase_web";
  private static final int ROUND = 1310;

  @Autowired private MockMvc mvc;
  @Autowired private RoundStore rounds;
  @Autowired private AccountStore accounts;
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
  @DisplayName("회원이 구매 등록하면 항목별 결과를 주고, 명령 조회도 같은 결과다. 다른 회원은 404, 비회원은 401")
  void memberPurchaseAndOperation() throws Exception {
    Instant now = rounds.dbNow();
    rounds.register(
        new RoundSchedule(
            ROUND, now.minus(Duration.ofDays(1)), now.plusSeconds(3600), now.plusSeconds(7200), 1));
    MemberPrincipal member =
        MemberPrincipal.of(accounts.login(OAuthProvider.GOOGLE, "buyer"), OAuthProvider.GOOGLE);
    MemberPrincipal other =
        MemberPrincipal.of(accounts.login(OAuthProvider.GOOGLE, "other"), OAuthProvider.GOOGLE);

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
                        .content("{\"operation\":\"REGISTER_PURCHASE\"}"))
                .andExpect(status().isOk())
                .andReturn())
            .get("token")
            .asText();

    MvcResult registered =
        mvc.perform(
                post("/api/v1/me/lotto/purchases")
                    .cookie(session)
                    .with(oauth2Login().oauth2User(member))
                    .header("X-CSRF-TOKEN", csrf)
                    .header("X-Command-Token", command)
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .contentType("application/json")
                    .content(
                        "{\"round\":"
                            + ROUND
                            + ",\"entries\":[{\"clientItemId\":\"a\",\"numbers\":[6,5,4,3,2,1]},"
                            + "{\"clientItemId\":\"b\",\"numbers\":[7,8,9,10,11,12]}]}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items.length()").value(2))
            .andExpect(jsonPath("$.data.items[0].outcome").value("CREATED"))
            .andExpect(jsonPath("$.data.lateRegistration").value(false))
            .andReturn();
    String operationId = body(registered).get("operationId").asText();

    MvcResult found =
        mvc.perform(get("/api/v1/operations/" + operationId).with(oauth2Login().oauth2User(member)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("SUCCEEDED"))
            .andReturn();
    assertThat(body(found).get("data")).isEqualTo(body(registered).get("data"));
    mvc.perform(get("/api/v1/operations/" + operationId).with(oauth2Login().oauth2User(other)))
        .andExpect(status().isNotFound());

    MvcResult guest = mvc.perform(post("/api/v1/guest-credentials")).andReturn();
    mvc.perform(
            post("/api/v1/me/lotto/purchases")
                .cookie(guest.getResponse().getCookie("__Host-lottery_guest"))
                .header("X-CSRF-TOKEN", body(guest).get("csrfToken").asText())
                .header("X-Command-Token", command)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType("application/json")
                .content("{\"round\":" + ROUND + ",\"entries\":[]}"))
        .andExpect(status().isUnauthorized());
  }

  private JsonNode body(MvcResult result) throws Exception {
    return json.readTree(result.getResponse().getContentAsString());
  }
}
