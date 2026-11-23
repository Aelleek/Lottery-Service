package com.lottery.lottery_service.record.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lottery.lottery_service.account.AccountStore;
import com.lottery.lottery_service.account.ClaimResult;
import com.lottery.lottery_service.account.GuestCredentialStore;
import com.lottery.lottery_service.account.SubjectClaimStore;
import com.lottery.lottery_service.auth.MemberPrincipal;
import com.lottery.lottery_service.auth.OAuthProvider;
import com.lottery.lottery_service.common.number.LottoNumbers;
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
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 비회원 기록 가져오기 API의 오류 경로를 실제 보안 필터와 MySQL로 확인한다. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(OpenApiContract.Config.class)
class GuestImportWebMySqlTest extends MySqlContainerSupport {

  private static final String DB = "guest_import_web";
  private static final int OPEN = 1330;

  @Autowired private MockMvc mvc;
  @Autowired private AccountStore accounts;
  @Autowired private GuestCredentialStore guests;
  @Autowired private SubjectClaimStore claims;
  @Autowired private RoundStore rounds;
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
  @DisplayName("다른 회원의 귀속 ID는 404이고 job이 없다. 같은 키 재전송은 같은 명령(202), 같은 키에 다른 귀속 ID는 409이고 job은 하나다")
  void importErrorPaths() throws Exception {
    Instant now = rounds.dbNow();
    rounds.register(
        new RoundSchedule(
            OPEN,
            now.minus(Duration.ofDays(1)),
            now.plus(Duration.ofDays(6)),
            now.plus(Duration.ofDays(6)),
            1));
    long guest = guests.issue().subjectId();
    jdbc.update(
        "INSERT INTO lotto_record (public_id, owner_subject_id, round_no, numbers_key,"
            + " recommended_at, created_at, expires_at) VALUES (UUID_TO_BIN(UUID()), ?, ?, ?,"
            + " NOW(6), NOW(6), NOW(6) + INTERVAL 30 DAY)",
        guest,
        OPEN,
        LottoNumbers.of(1, 2, 3, 4, 5, 6).key());
    MemberPrincipal owner =
        MemberPrincipal.of(
            accounts.login(OAuthProvider.KAKAO, "import-owner"), OAuthProvider.KAKAO);
    MemberPrincipal other =
        MemberPrincipal.of(
            accounts.login(OAuthProvider.KAKAO, "import-other"), OAuthProvider.KAKAO);
    assertThat(claims.claim(guest, owner.subjectId())).isEqualTo(ClaimResult.CLAIMED);
    String claimId =
        jdbc.queryForObject(
            "SELECT BIN_TO_UUID(public_id) FROM subject_claim WHERE guest_subject_id = ?",
            String.class,
            guest);
    Client mine = client(owner);
    Client theirs = client(other);

    theirs
        .start(claimId, UUID.randomUUID(), theirs.token())
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    assertThat(importJobs()).isZero();

    UUID key = UUID.randomUUID();
    String token = mine.token();
    String first =
        body(mine.start(claimId, key, token).andExpect(status().isAccepted()).andReturn())
            .path("operationId")
            .asString();
    String again =
        body(mine.start(claimId, key, token).andExpect(status().isAccepted()).andReturn())
            .path("operationId")
            .asString();
    assertThat(again).isEqualTo(first);
    mine.start(UUID.randomUUID().toString(), key, token)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"))
        .andExpect(jsonPath("$.retryable").value(false));
    assertThat(importJobs()).isEqualTo(1);

    mvc.perform(get("/api/v1/operations/" + first).with(oauth2Login().oauth2User(other)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));
  }

  private long importJobs() {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM job WHERE job_type = 'HISTORY_IMPORT'", Long.class);
  }

  private Client client(MemberPrincipal member) throws Exception {
    MvcResult state =
        mvc.perform(get("/api/v1/session").with(oauth2Login().oauth2User(member))).andReturn();
    String csrf = body(state).get("csrfToken").asString();
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

    String token() throws Exception {
      return body(mvc.perform(
                  post("/api/v1/command-tokens")
                      .cookie(session)
                      .with(oauth2Login().oauth2User(member))
                      .header("X-CSRF-TOKEN", csrf)
                      .contentType("application/json")
                      .content("{\"operation\":\"IMPORT_GUEST\"}"))
              .andExpect(status().isOk())
              .andReturn())
          .get("token")
          .asString();
    }

    ResultActions start(String claimId, UUID key, String token) throws Exception {
      return mvc.perform(
          post("/api/v1/me/guest-claims/" + claimId + "/imports")
              .cookie(session)
              .with(oauth2Login().oauth2User(member))
              .header("X-CSRF-TOKEN", csrf)
              .header("X-Command-Token", token)
              .header("Idempotency-Key", key.toString())
              .contentType("application/json")
              .content("{\"confirm\":true}"));
    }
  }
}
