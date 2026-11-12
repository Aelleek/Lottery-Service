package com.lottery.lottery_service.acceptance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lottery.lottery_service.account.AccountStore;
import com.lottery.lottery_service.auth.MemberPrincipal;
import com.lottery.lottery_service.auth.OAuthProvider;
import com.lottery.lottery_service.support.MySqlContainerSupport;
import jakarta.servlet.http.Cookie;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;

/**
 * openapi의 operation별 {@code security}대로 주체 경계가 지켜지는지 모든 operation에 대해 확인한다(03 6절 주체와 권한). 주체 없음,
 * 회원 API에 비회원, 비회원 API에 회원, 관리자 API에 관리자 역할 없는 회원을 보낸다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SubjectBoundaryWebMySqlTest extends MySqlContainerSupport {

  private static final String DB = "subject_boundary";
  private static final Path OPENAPI = Path.of("docs/contracts/openapi.yaml");
  private static final List<String> METHODS = List.of("get", "post", "put", "patch", "delete");
  private static final List<String> STATE_TABLES =
      List.of(
          "command_operation",
          "lotto_record",
          "recommendation_request",
          "verification_challenge",
          "delivery_message",
          "job",
          "admin_audit_event");

  @Autowired private MockMvc mvc;
  @Autowired private AccountStore accounts;
  @Autowired private ObjectMapper json;
  @Autowired private JdbcTemplate jdbc;

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
  @DisplayName("주체가 필요한 operation은 주체 없이 부르면 모두 401 UNAUTHENTICATED이다")
  void anonymousRejected() throws Exception {
    List<Operation> protectedOps =
        operations().stream().filter(o -> !o.schemes().isEmpty()).toList();
    assertThat(protectedOps).hasSizeGreaterThan(20);
    Map<String, Long> before = stateCounts();
    for (Operation op : protectedOps) {
      expect(mvc.perform(withBody(request(op.method(), op.url()))).andReturn(), op, 401);
    }
    assertThat(stateCounts()).isEqualTo(before);
  }

  @Test
  @DisplayName("회원·관리자 전용 operation은 유효한 비회원 자격과 CSRF 토큰으로 불러도 401이고 아무것도 바뀌지 않는다")
  void guestRejectedFromMemberOperations() throws Exception {
    MvcResult issued = mvc.perform(post("/api/v1/guest-credentials")).andReturn();
    Cookie guest = issued.getResponse().getCookie("__Host-lottery_guest");
    String csrf = body(issued).get("csrfToken").asText();
    List<Operation> memberOnly =
        operations().stream()
            .filter(o -> !o.schemes().isEmpty() && !o.schemes().contains("GuestCredential"))
            .toList();
    assertThat(memberOnly).hasSizeGreaterThan(15);
    Map<String, Long> before = stateCounts();
    for (Operation op : memberOnly) {
      MockHttpServletRequestBuilder r =
          commandHeaders(request(op.method(), op.url()).cookie(guest), csrf);
      expect(mvc.perform(r).andReturn(), op, 401);
    }
    assertThat(stateCounts()).isEqualTo(before);
  }

  @Test
  @DisplayName("비회원 전용 operation은 회원 세션으로 부르면 401이다")
  void memberRejectedFromGuestOperations() throws Exception {
    MemberPrincipal member = member("boundary-guest-only");
    Session session = session(member);
    List<Operation> guestOnly =
        operations().stream().filter(o -> o.schemes().equals(Set.of("GuestCredential"))).toList();
    assertThat(guestOnly).isNotEmpty();
    for (Operation op : guestOnly) {
      MockHttpServletRequestBuilder r =
          commandHeaders(
              request(op.method(), op.url())
                  .cookie(session.cookie())
                  .with(oauth2Login().oauth2User(member)),
              session.csrf());
      expect(mvc.perform(r).andReturn(), op, 401);
    }
  }

  @Test
  @DisplayName("관리자 operation은 관리자 역할이 없는 회원이 부르면 403 FORBIDDEN이고 아무것도 바뀌지 않는다")
  void memberWithoutRoleRejectedFromAdminOperations() throws Exception {
    MemberPrincipal member = member("boundary-no-role");
    Session session = session(member);
    List<Operation> adminOps =
        operations().stream().filter(o -> o.schemes().contains("AdminSession")).toList();
    assertThat(adminOps).hasSizeGreaterThan(5);
    Map<String, Long> before = stateCounts();
    for (Operation op : adminOps) {
      MockHttpServletRequestBuilder r =
          commandHeaders(
              request(op.method(), op.url())
                  .cookie(session.cookie())
                  .with(oauth2Login().oauth2User(member)),
              session.csrf());
      expect(mvc.perform(r).andReturn(), op, 403);
    }
    assertThat(stateCounts()).isEqualTo(before);
  }

  private void expect(MvcResult result, Operation op, int status) throws Exception {
    assertThat(result.getResponse().getStatus()).as(op.toString()).isEqualTo(status);
    assertThat(body(result).path("code").asText())
        .as(op.toString())
        .isEqualTo(status == 401 ? "UNAUTHENTICATED" : "FORBIDDEN");
  }

  private static MockHttpServletRequestBuilder commandHeaders(
      MockHttpServletRequestBuilder request, String csrf) {
    return withBody(
        request
            .header("X-CSRF-TOKEN", csrf)
            .header("X-Command-Token", "not-a-token")
            .header("Idempotency-Key", UUID.randomUUID().toString()));
  }

  private static MockHttpServletRequestBuilder withBody(MockHttpServletRequestBuilder request) {
    return request.contentType("application/json").content("{}");
  }

  private Map<String, Long> stateCounts() {
    Map<String, Long> counts = new TreeMap<>();
    for (String table : STATE_TABLES) {
      counts.put(table, jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class));
    }
    return counts;
  }

  @SuppressWarnings("unchecked")
  private static List<Operation> operations() throws IOException {
    LoaderOptions options = new LoaderOptions();
    options.setMaxAliasesForCollections(1_000);
    Map<String, Object> spec = new Yaml(options).load(Files.readString(OPENAPI));
    Map<String, Map<String, Object>> paths = (Map<String, Map<String, Object>>) spec.get("paths");
    List<Operation> operations = new ArrayList<>();
    paths.forEach(
        (path, item) ->
            item.forEach(
                (method, operation) -> {
                  if (!METHODS.contains(method)) {
                    return;
                  }
                  Set<String> schemes = new HashSet<>();
                  List<Map<String, Object>> security =
                      (List<Map<String, Object>>)
                          ((Map<String, Object>) operation).getOrDefault("security", List.of());
                  security.forEach(requirement -> schemes.addAll(requirement.keySet()));
                  String url =
                      "/api/v1"
                          + path.replace("{round}", "1")
                              .replaceAll("\\{[A-Za-z]+}", UUID.randomUUID().toString());
                  operations.add(
                      new Operation(
                          HttpMethod.valueOf(method.toUpperCase(Locale.ROOT)), path, url, schemes));
                }));
    return operations;
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

  private JsonNode body(MvcResult result) throws Exception {
    return json.readTree(result.getResponse().getContentAsString());
  }

  private record Operation(HttpMethod method, String path, String url, Set<String> schemes) {
    @Override
    public String toString() {
      return method + " " + path;
    }
  }

  private record Session(Cookie cookie, String csrf) {}
}
