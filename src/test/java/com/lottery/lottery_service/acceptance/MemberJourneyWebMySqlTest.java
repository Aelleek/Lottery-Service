package com.lottery.lottery_service.acceptance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lottery.lottery_service.account.AccountStore;
import com.lottery.lottery_service.account.ClaimResult;
import com.lottery.lottery_service.account.SubjectClaimStore;
import com.lottery.lottery_service.auth.MemberPrincipal;
import com.lottery.lottery_service.auth.OAuthProvider;
import com.lottery.lottery_service.common.job.JobRunner;
import com.lottery.lottery_service.common.number.LottoNumber;
import com.lottery.lottery_service.common.number.LottoNumbers;
import com.lottery.lottery_service.result.domain.WinningNumbers;
import com.lottery.lottery_service.result.publish.ResultPublisher;
import com.lottery.lottery_service.result.source.OfficialDraw;
import com.lottery.lottery_service.round.RoundStore;
import com.lottery.lottery_service.round.domain.RoundSchedule;
import com.lottery.lottery_service.support.MySqlContainerSupport;
import jakarta.servlet.http.Cookie;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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

/**
 * 회원 한 명의 회차 흐름을 웹 API로 끝까지 확인한다(08 2절 단계 10). 추천 → 구매 등록 → 마감 → 결과 게시 → 판정 → 결과·내역·즉시 확인. 결과 원천은
 * 게시기 호출로, 로그인 때의 비회원 귀속은 귀속 저장소 호출로 대신한다(08 3절, 로그인 처리기는 {@code LoginSuccessHandlerMySqlTest}).
 */
@SpringBootTest
@AutoConfigureMockMvc
class MemberJourneyWebMySqlTest extends MySqlContainerSupport {

  private static final String DB = "member_journey_web";
  private static final int ROUND = 1320;
  private static final List<Integer> FOURTH = List.of(1, 2, 3, 4, 10, 11);

  @Autowired private MockMvc mvc;
  @Autowired private RoundStore rounds;
  @Autowired private AccountStore accounts;
  @Autowired private ResultPublisher publisher;
  @Autowired private JobRunner jobs;
  @Autowired private SubjectClaimStore claims;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private ObjectMapper json;

  private MemberPrincipal member;
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

  @Test
  @DisplayName("추천받은 번호와 직접 고른 번호를 구매 등록하고, 마감·게시·판정 뒤 결과·내역·등수 필터·즉시 확인이 같은 등수를 보여 준다")
  void recommendPurchasePublishAndView() throws Exception {
    Instant now = rounds.dbNow();
    rounds.register(
        new RoundSchedule(
            ROUND, now.minus(Duration.ofDays(1)), now.plusSeconds(3600), now.plusSeconds(7200), 1));
    member =
        MemberPrincipal.of(accounts.login(OAuthProvider.KAKAO, "journey"), OAuthProvider.KAKAO);
    MvcResult state =
        mvc.perform(get("/api/v1/session").with(oauth2Login().oauth2User(member))).andReturn();
    csrf = body(state).get("csrfToken").asText();
    String setCookie = state.getResponse().getHeader("Set-Cookie");
    session =
        new Cookie(
            "__Host-lottery_session",
            setCookie.substring(setCookie.indexOf('=') + 1, setCookie.indexOf(';')));

    JsonNode recommended =
        body(mvc.perform(
                    command(
                            post("/api/v1/lotto/recommendations"),
                            "{\"operation\":\"RECOMMEND\",\"round\":"
                                + ROUND
                                + ",\"scheduleVersion\":\"1\"}")
                        .content(
                            "{\"round\":"
                                + ROUND
                                + ",\"scheduleVersion\":\"1\",\"include\":[],\"exclude\":[]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sets.length()").value(5))
                .andReturn())
            .get("data")
            .get("sets");
    List<Integer> chosen = new ArrayList<>();
    recommended.get(0).get("numbers").forEach(n -> chosen.add(n.asInt()));

    mvc.perform(
            command(post("/api/v1/me/lotto/purchases"), "{\"operation\":\"REGISTER_PURCHASE\"}")
                .content(
                    "{\"round\":"
                        + ROUND
                        + ",\"entries\":[{\"clientItemId\":\"rec\",\"numbers\":"
                        + chosen
                        + "},{\"clientItemId\":\"own\",\"numbers\":"
                        + FOURTH
                        + "}]}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.items.length()").value(2))
        .andExpect(jsonPath("$.data.lateRegistration").value(false));

    jdbc.update(
        "UPDATE lotto_round SET sales_close_at = NOW(6), draw_at = NOW(6) WHERE round_no = ?",
        ROUND);
    assertThat(rounds.sealDue()).contains(ROUND);
    publisher.receive(
        new OfficialDraw(
            ROUND,
            new WinningNumbers(LottoNumbers.of(1, 2, 3, 4, 5, 6), LottoNumber.of(7)),
            2_000_000_000L,
            10L,
            new byte[32]));
    drainJobs();

    mvc.perform(get("/api/v1/lotto/rounds/" + ROUND + "/result"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("PUBLISHED"))
        .andExpect(jsonPath("$.bonus").value(7))
        .andExpect(jsonPath("$.numbers[5]").value(6));

    JsonNode items =
        body(mvc.perform(
                    get("/api/v1/me/lotto/records")
                        .param("round", Integer.toString(ROUND))
                        .with(oauth2Login().oauth2User(member)))
                .andExpect(status().isOk())
                .andReturn())
            .get("items");
    assertThat(items).hasSize(6);
    for (JsonNode item : items) {
      assertThat(item.get("evaluation").get("state").asText()).isEqualTo("EVALUATED");
      List<Integer> numbers = new ArrayList<>();
      item.get("numbers").forEach(n -> numbers.add(n.asInt()));
      if (numbers.equals(FOURTH)) {
        assertThat(item.get("purchased").asBoolean()).isTrue();
        assertThat(item.get("recommended").asBoolean()).isFalse();
        assertThat(item.get("evaluation").get("rank").asInt()).isEqualTo(4);
        assertThat(item.get("evaluation").get("prizePerGame").asText()).isEqualTo("50000");
      } else if (numbers.equals(chosen)) {
        assertThat(item.get("purchased").asBoolean()).isTrue();
        assertThat(item.get("recommended").asBoolean()).isTrue();
      } else {
        assertThat(item.get("purchased").asBoolean()).isFalse();
      }
    }

    mvc.perform(
            get("/api/v1/me/lotto/records")
                .param("rank", "4")
                .with(oauth2Login().oauth2User(member)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[?(@.numbers == [1,2,3,4,10,11])]").exists());

    mvc.perform(
            post("/api/v1/lotto/check")
                .contentType("application/json")
                .content("{\"round\":" + ROUND + ",\"sets\":[" + FOURTH + "]}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].evaluation.rank").value(4))
        .andExpect(jsonPath("$.items[0].evaluation.prizePerGame").value("50000"));
  }

  @Test
  @DisplayName(
      "비회원이 추천받은 뒤 로그인해 귀속하고 가져오면, 마감·게시·판정 뒤 회원 내역에 그 5세트가 추천 기록으로 판정되어 있다. 귀속 뒤 옛 쿠키와 새 쿠키로는 그 기록을 보지 못한다(T32)")
  void guestRecommendClaimImportAndView() throws Exception {
    int round = ROUND + 1;
    Instant now = rounds.dbNow();
    rounds.register(
        new RoundSchedule(
            round, now.minus(Duration.ofDays(1)), now.plusSeconds(3600), now.plusSeconds(7200), 1));
    MvcResult issued = mvc.perform(post("/api/v1/guest-credentials")).andReturn();
    Cookie guest = issued.getResponse().getCookie("__Host-lottery_guest");
    String guestCsrf = body(issued).get("csrfToken").asText();
    String guestToken =
        body(mvc.perform(
                    post("/api/v1/command-tokens")
                        .cookie(guest)
                        .header("X-CSRF-TOKEN", guestCsrf)
                        .contentType("application/json")
                        .content(
                            "{\"operation\":\"RECOMMEND\",\"round\":"
                                + round
                                + ",\"scheduleVersion\":\"1\"}"))
                .andExpect(status().isOk())
                .andReturn())
            .get("token")
            .asText();
    JsonNode sets =
        body(mvc.perform(
                    post("/api/v1/lotto/recommendations")
                        .cookie(guest)
                        .header("X-CSRF-TOKEN", guestCsrf)
                        .header("X-Command-Token", guestToken)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType("application/json")
                        .content(
                            "{\"round\":"
                                + round
                                + ",\"scheduleVersion\":\"1\",\"include\":[],\"exclude\":[]}"))
                .andExpect(status().isOk())
                .andReturn())
            .get("data")
            .get("sets");
    List<String> guestRecords = new ArrayList<>();
    sets.forEach(set -> guestRecords.add(set.get("recordId").asText()));
    mvc.perform(
            get("/api/v1/guest/lotto/records")
                .param("round", Integer.toString(round))
                .cookie(guest))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(5));

    member =
        MemberPrincipal.of(accounts.login(OAuthProvider.NAVER, "from-guest"), OAuthProvider.NAVER);
    long guestSubject =
        jdbc.queryForObject(
            "SELECT owner_subject_id FROM lotto_record WHERE public_id = UUID_TO_BIN(?)",
            Long.class,
            guestRecords.get(0));
    assertThat(claims.claim(guestSubject, member.subjectId())).isEqualTo(ClaimResult.CLAIMED);
    mvc.perform(
            get("/api/v1/guest/lotto/records")
                .param("round", Integer.toString(round))
                .cookie(guest))
        .andExpect(status().isUnauthorized());
    Cookie lost =
        mvc.perform(post("/api/v1/guest-credentials"))
            .andReturn()
            .getResponse()
            .getCookie("__Host-lottery_guest");
    mvc.perform(
            get("/api/v1/guest/lotto/records").param("round", Integer.toString(round)).cookie(lost))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(0));
    String claimId =
        jdbc.queryForObject(
            "SELECT BIN_TO_UUID(public_id) FROM subject_claim WHERE guest_subject_id = ?",
            String.class,
            guestSubject);
    MvcResult state =
        mvc.perform(get("/api/v1/session").with(oauth2Login().oauth2User(member))).andReturn();
    csrf = body(state).get("csrfToken").asText();
    String setCookie = state.getResponse().getHeader("Set-Cookie");
    session =
        new Cookie(
            "__Host-lottery_session",
            setCookie.substring(setCookie.indexOf('=') + 1, setCookie.indexOf(';')));

    String operationId =
        body(mvc.perform(
                    command(
                            post("/api/v1/me/guest-claims/" + claimId + "/imports"),
                            "{\"operation\":\"IMPORT_GUEST\"}")
                        .content("{\"confirm\":true}"))
                .andExpect(status().isAccepted())
                .andReturn())
            .get("operationId")
            .asText();
    drainJobs();
    mvc.perform(get("/api/v1/operations/" + operationId).with(oauth2Login().oauth2User(member)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SUCCEEDED"));

    jdbc.update(
        "UPDATE lotto_round SET sales_close_at = NOW(6), draw_at = NOW(6) WHERE round_no = ?",
        round);
    assertThat(rounds.sealDue()).contains(round);
    publisher.receive(
        new OfficialDraw(
            round,
            new WinningNumbers(LottoNumbers.of(40, 41, 42, 43, 44, 45), LottoNumber.of(39)),
            2_000_000_000L,
            10L,
            new byte[32]));
    drainJobs();

    JsonNode items =
        body(mvc.perform(
                    get("/api/v1/me/lotto/records")
                        .param("round", Integer.toString(round))
                        .with(oauth2Login().oauth2User(member)))
                .andExpect(status().isOk())
                .andReturn())
            .get("items");
    assertThat(items).hasSize(5);
    for (JsonNode item : items) {
      assertThat(item.get("recommended").asBoolean()).isTrue();
      assertThat(item.get("purchased").asBoolean()).isFalse();
      assertThat(item.get("evaluation").get("state").asText()).isEqualTo("EVALUATED");
    }
  }

  private MockHttpServletRequestBuilder command(
      MockHttpServletRequestBuilder request, String tokenInput) throws Exception {
    String token =
        body(mvc.perform(
                    post("/api/v1/command-tokens")
                        .cookie(session)
                        .with(oauth2Login().oauth2User(member))
                        .header("X-CSRF-TOKEN", csrf)
                        .contentType("application/json")
                        .content(tokenInput))
                .andExpect(status().isOk())
                .andReturn())
            .get("token")
            .asText();
    return request
        .cookie(session)
        .with(oauth2Login().oauth2User(member))
        .header("X-CSRF-TOKEN", csrf)
        .header("X-Command-Token", token)
        .header("Idempotency-Key", UUID.randomUUID().toString())
        .contentType("application/json");
  }

  private void drainJobs() {
    int ran = 0;
    while (ran < 20 && jobs.runOnce().isPresent()) {
      ran++;
    }
    assertThat(ran).isLessThan(20);
  }

  private JsonNode body(MvcResult result) throws Exception {
    return json.readTree(result.getResponse().getContentAsString());
  }
}
