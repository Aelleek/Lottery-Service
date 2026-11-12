package com.lottery.lottery_service.acceptance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lottery.lottery_service.account.AccountStore;
import com.lottery.lottery_service.admin.mfa.AdminMfaStore;
import com.lottery.lottery_service.admin.mfa.Totp;
import com.lottery.lottery_service.auth.MemberPrincipal;
import com.lottery.lottery_service.auth.OAuthProvider;
import com.lottery.lottery_service.round.RoundStore;
import com.lottery.lottery_service.round.domain.RoundSchedule;
import com.lottery.lottery_service.support.MySqlContainerSupport;
import com.lottery.lottery_service.support.OpenApiContract;
import jakarta.servlet.http.Cookie;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.session.SessionRepository;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;

/**
 * openapi 요청 스키마의 상한·형식(JSON 타입, 개수, 범위, 길이, 패턴, 허용 값, 중복, 필수)을 하나씩 어긴 본문을 회원 operation에 보내 서버가
 * 400·422로 거절하고 아무것도 바꾸지 않는지 확인한다(03 6.4, 화면 검사만 믿지 않는다). 각 요청은 유효한 세션·CSRF·명령 토큰을 갖춰 입력 검사까지 닿게
 * 한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(OpenApiContract.Config.class)
@ExtendWith(OutputCaptureExtension.class)
class InputLimitWebMySqlTest extends MySqlContainerSupport {

  private static final String DB = "input_limit";
  private static final int ROUND = 1400;
  private static final Path OPENAPI = Path.of("docs/contracts/openapi.yaml");
  private static final Set<String> REJECTED = Set.of("VALIDATION_FAILED", "CONDITION_INVALID");

  /**
   * 입력 검사보다 앞선 규칙으로 403 {@code FORBIDDEN}이 맞는 필드. 명령 종류가 없거나 모르는 값이면 "허용되지 않은 명령"(06 2절)이고, 추천 회차는
   * 명령 토큰에 묶여 있어 다른 회차는 토큰 불일치다(06 1.1절).
   */
  private static final Set<String> FORBIDDEN_FIELDS =
      Set.of("/command-tokens /operation", "/lotto/recommendations /round");

  private static final List<String> STATE_TABLES =
      List.of(
          "command_operation",
          "lotto_record",
          "recommendation_request",
          "verification_challenge",
          "consent_event",
          "member_contact",
          "delivery_message",
          "job");

  @Autowired private MockMvc mvc;
  @Autowired private AccountStore accounts;
  @Autowired private RoundStore rounds;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private ObjectMapper json;
  @Autowired private AdminMfaStore mfa;
  @Autowired private SessionRepository<? extends org.springframework.session.Session> sessions;

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
  @DisplayName("회원 operation의 요청 스키마 제약을 하나씩 어기면 모두 400·422이고 상태 테이블은 그대로다")
  void everyDeclaredLimitIsEnforced(CapturedOutput output) throws Exception {
    registerRound();
    MemberPrincipal member =
        MemberPrincipal.of(accounts.login(OAuthProvider.KAKAO, "input-limit"), OAuthProvider.KAKAO);
    Session session = session(member);
    String round = Integer.toString(ROUND);
    List<Target> targets =
        List.of(
            new Target(
                HttpMethod.POST,
                "/command-tokens",
                "/command-tokens",
                null,
                "{\"operation\":\"RECOMMEND\",\"round\":" + round + ",\"scheduleVersion\":\"1\"}"),
            new Target(
                HttpMethod.POST,
                "/lotto/recommendations",
                "/lotto/recommendations",
                "RECOMMEND",
                "{\"round\":"
                    + round
                    + ",\"scheduleVersion\":\"1\",\"include\":[7],\"exclude\":[8]}"),
            new Target(
                HttpMethod.POST,
                "/me/lotto/purchases",
                "/me/lotto/purchases",
                "REGISTER_PURCHASE",
                "{\"round\":"
                    + round
                    + ",\"entries\":[{\"clientItemId\":\"a\",\"numbers\":[1,2,3,4,5,6]}]}"),
            new Target(
                HttpMethod.POST,
                "/me/lotto/records/{recordId}/cancel-purchase",
                "/me/lotto/records/" + UUID.randomUUID() + "/cancel-purchase",
                "CANCEL_PURCHASE",
                "{\"expectedVersion\":\"0\"}"),
            new Target(
                HttpMethod.POST,
                "/lotto/check",
                "/lotto/check",
                null,
                "{\"round\":" + round + ",\"sets\":[[1,2,3,4,5,6]]}"),
            new Target(
                HttpMethod.POST,
                "/me/notification-verifications",
                "/me/notification-verifications",
                "REQUEST_VERIFICATION",
                "{\"phoneNumber\":\"+821012345678\",\"expectedContactVersion\":\"0\"}"),
            new Target(
                HttpMethod.POST,
                "/me/notification-verifications/{challengeId}/verify",
                "/me/notification-verifications/" + UUID.randomUUID() + "/verify",
                "VERIFY_CONTACT",
                "{\"code\":\"123456\"}"),
            new Target(
                HttpMethod.PUT,
                "/me/notification-preference",
                "/me/notification-preference",
                "CHANGE_PREFERENCE",
                "{\"enabled\":true,\"expectedVersion\":\"0\",\"verifiedContactVersion\":\"0\","
                    + "\"policyVersion\":\"2026-11\"}"));
    Map<String, Long> before = stateCounts();
    int sent =
        sweep(
            targets,
            (target, r) -> {
              r.cookie(session.cookie())
                  .with(oauth2Login().oauth2User(member))
                  .header("X-CSRF-TOKEN", session.csrf());
              if (target.operation() != null) {
                r.header("X-Command-Token", commandToken(session, member, target.operation()));
              }
            });
    assertThat(sent).isGreaterThan(40);
    assertThat(stateCounts()).isEqualTo(before);
    assertQuietLogs(output);
  }

  @Test
  @DisplayName("관리자 operation의 요청 스키마 제약을 하나씩 어기면 모두 400·422이고 감사·결과·job은 그대로다")
  void everyDeclaredAdminLimitIsEnforced(CapturedOutput output) throws Exception {
    int adminRound = ROUND + 1;
    jdbc.update(
        "INSERT INTO lotto_round (round_no, sales_open_at, sales_close_at, draw_at, schedule_version)"
            + " VALUES (?, NOW(6) - INTERVAL 7 DAY, NOW(6) - INTERVAL 2 HOUR,"
            + " NOW(6) - INTERVAL 1 HOUR, 1)",
        adminRound);
    MemberPrincipal admin =
        MemberPrincipal.of(
            accounts.login(OAuthProvider.GOOGLE, "input-admin"), OAuthProvider.GOOGLE);
    jdbc.update(
        "INSERT INTO admin_grant (member_id, role, granted_at) VALUES (?, 'ADMIN', NOW(6))",
        admin.memberId());
    byte[] secret = decode(mfa.enroll(admin.memberId()).secret());
    long step = Totp.step(Instant.now());
    mfa.confirm(admin.memberId(), Totp.code(secret, step - 1));
    Session member = session(admin);
    MvcResult started =
        mvc.perform(
                post("/api/v1/admin/session")
                    .cookie(member.cookie())
                    .with(oauth2Login().oauth2User(admin))
                    .header("X-CSRF-TOKEN", member.csrf())
                    .contentType("application/json")
                    .content("{\"code\":\"" + Totp.code(secret, step) + "\"}"))
            .andReturn();
    assertThat(started.getResponse().getStatus()).isEqualTo(200);
    Session session =
        new Session(
            cookie(started.getResponse().getHeader("Set-Cookie")),
            body(started).get("csrfToken").asText());
    reauthenticate(session.cookie());
    String round = Integer.toString(adminRound);
    List<Target> targets =
        List.of(
            new Target(
                HttpMethod.POST,
                "/admin/rounds/{round}/notification-dispatches",
                "/admin/rounds/" + round + "/notification-dispatches",
                null,
                "{\"reason\":\"확인\"}"),
            new Target(
                HttpMethod.POST,
                "/admin/draw-results/{resultId}/publication",
                "/admin/draw-results/" + UUID.randomUUID() + "/publication",
                null,
                "{\"expectedPublicationVersion\":\"0\",\"reason\":\"확인\"}"),
            new Target(
                HttpMethod.POST,
                "/admin/rounds/{round}/draw-results",
                "/admin/rounds/" + round + "/draw-results",
                null,
                "{\"numbers\":[1,2,3,4,5,6],\"bonus\":7,\"expectedPublicationVersion\":\"0\","
                    + "\"reason\":\"확인\"}"));
    Map<String, Long> before = adminStateCounts();
    int sent =
        sweep(
            targets,
            (target, r) ->
                r.cookie(session.cookie())
                    .with(oauth2Login().oauth2User(admin))
                    .header("X-CSRF-TOKEN", session.csrf()));
    assertThat(sent).isGreaterThan(15);
    assertThat(adminStateCounts()).isEqualTo(before);
    assertQuietLogs(output);
  }

  @Test
  @DisplayName("조회 변수와 경로 회차, 명령 헤더가 openapi 형식·범위를 벗어나면 400 VALIDATION_FAILED다")
  void parameterLimitsAreEnforced(CapturedOutput output) throws Exception {
    registerRound();
    MemberPrincipal member =
        MemberPrincipal.of(
            accounts.login(OAuthProvider.NAVER, "input-params"), OAuthProvider.NAVER);
    Session session = session(member);
    String longCursor = "a".repeat(4097);
    List<String> gets =
        List.of(
            "/me/lotto/records?round=0",
            "/me/lotto/records?round=x",
            "/me/lotto/records?purchased=maybe",
            "/me/lotto/records?rank=6",
            "/me/lotto/records?cursor=" + longCursor,
            "/lotto/rounds/0/result",
            "/lotto/rounds/x/result");
    List<String> failures = new ArrayList<>();
    for (String url : gets) {
      MvcResult result =
          mvc.perform(
                  get("/api/v1" + url)
                      .cookie(session.cookie())
                      .with(oauth2Login().oauth2User(member)))
              .andReturn();
      expectValidation(result, "GET " + url, failures);
    }
    String purchase =
        "{\"round\":"
            + ROUND
            + ",\"entries\":[{\"clientItemId\":\"a\",\"numbers\":[1,2,3,4,5,6]}]}";
    for (String[] headers :
        new String[][] {
          {UUID.randomUUID().toString().substring(1), "valid"},
          {UUID.randomUUID().toString(), "t".repeat(4097)}
        }) {
      String token =
          "valid".equals(headers[1])
              ? commandToken(session, member, "REGISTER_PURCHASE")
              : headers[1];
      MvcResult result =
          mvc.perform(
                  post("/api/v1/me/lotto/purchases")
                      .cookie(session.cookie())
                      .with(oauth2Login().oauth2User(member))
                      .header("X-CSRF-TOKEN", session.csrf())
                      .header("Idempotency-Key", headers[0])
                      .header("X-Command-Token", token)
                      .contentType("application/json")
                      .content(purchase))
              .andReturn();
      expectValidation(
          result,
          "POST purchases key=" + headers[0].length() + " token=" + token.length(),
          failures);
    }
    assertThat(failures).isEmpty();
    assertQuietLogs(output);
  }

  private void expectValidation(MvcResult result, String label, List<String> failures)
      throws Exception {
    int status = result.getResponse().getStatus();
    String code = body(result).path("code").asText();
    if (status != 400 || !"VALIDATION_FAILED".equals(code)) {
      failures.add(label + " -> " + status + " " + code);
    }
  }

  private static void assertQuietLogs(CapturedOutput output) {
    assertThat(output.getAll())
        .as("거절한 요청은 error 로그를 남기지 않고, 보낸 전화번호와 코드를 로그에 싣지 않는다")
        .doesNotContain(" ERROR ")
        .doesNotContain("821012345678")
        .doesNotContain("1012345678");
  }

  private int sweep(List<Target> targets, Authenticator authenticate) throws Exception {
    JsonNode spec = spec();
    int sent = 0;
    List<String> failures = new ArrayList<>();
    for (Target target : targets) {
      JsonNode schema =
          resolve(
              spec,
              spec.path("paths")
                  .path(target.template())
                  .path(target.method().name().toLowerCase(Locale.ROOT))
                  .path("requestBody")
                  .path("content")
                  .path("application/json")
                  .path("schema"));
      List<Mutation> mutations = new ArrayList<>();
      mutate(spec, schema, json.readTree(target.baseline()), "", mutations);
      assertThat(mutations).as(target.template()).isNotEmpty();
      for (Mutation mutation : mutations) {
        MockHttpServletRequestBuilder r =
            request(target.method(), "/api/v1" + target.url())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType("application/json")
                .content(json.writeValueAsString(mutation.body()));
        authenticate.apply(target, r);
        MvcResult result = mvc.perform(r).andReturn();
        int status = result.getResponse().getStatus();
        String code = body(result).path("code").asText();
        boolean rejected = (status == 400 || status == 422) && REJECTED.contains(code);
        boolean forbidden =
            status == 403
                && "FORBIDDEN".equals(code)
                && FORBIDDEN_FIELDS.contains(target.template() + " " + mutation.field());
        if (!rejected && !forbidden) {
          failures.add(
              target.template() + " " + mutation.description() + " -> " + status + " " + code);
        }
        sent++;
      }
    }
    assertThat(failures).isEmpty();
    return sent;
  }

  private Map<String, Long> adminStateCounts() {
    Map<String, Long> counts = new TreeMap<>();
    for (String table : List.of("admin_audit_event", "draw_result", "job", "command_operation")) {
      counts.put(table, jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class));
    }
    return counts;
  }

  @SuppressWarnings("unchecked")
  private void reauthenticate(Cookie session) {
    String id = new String(Base64.getDecoder().decode(session.getValue()), StandardCharsets.UTF_8);
    SessionRepository<org.springframework.session.Session> repository =
        (SessionRepository<org.springframework.session.Session>) sessions;
    org.springframework.session.Session stored = repository.findById(id);
    stored.setAttribute(
        "com.lottery.lottery_service.auth.Reauthentication.completedAt", Instant.now());
    repository.save(stored);
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

  @FunctionalInterface
  private interface Authenticator {
    void apply(Target target, MockHttpServletRequestBuilder request) throws Exception;
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

  private void mutate(
      JsonNode spec, JsonNode schemaNode, JsonNode root, String pointer, List<Mutation> out) {
    JsonNode schema = resolve(spec, schemaNode);
    JsonNode value = pointer.isEmpty() ? root : root.at(pointer);
    if (value.isMissingNode()) {
      return;
    }
    if (!pointer.isEmpty() && schema.has("type")) {
      JsonNode wrong =
          "string".equals(schema.path("type").asText())
              ? json.createObjectNode().put("x", 1)
              : json.getNodeFactory().textNode("x");
      out.add(replace(root, pointer, wrong, "type"));
    }
    if (schema.has("maxLength")) {
      out.add(
          replace(
              root,
              pointer,
              json.getNodeFactory().textNode("a".repeat(schema.get("maxLength").asInt() + 1)),
              "maxLength"));
    }
    if (schema.path("minLength").asInt() > 0) {
      out.add(replace(root, pointer, json.getNodeFactory().textNode(""), "minLength"));
    }
    if (schema.has("pattern")) {
      out.add(replace(root, pointer, json.getNodeFactory().textNode("x-1"), "pattern"));
    }
    if (schema.has("enum") && value.isTextual()) {
      out.add(replace(root, pointer, json.getNodeFactory().textNode("NOT_IN_ENUM"), "enum"));
    }
    if (schema.has("minimum")) {
      out.add(
          replace(
              root,
              pointer,
              json.getNodeFactory().numberNode(schema.get("minimum").asLong() - 1),
              "minimum"));
    }
    if (schema.has("maximum")) {
      out.add(
          replace(
              root,
              pointer,
              json.getNodeFactory().numberNode(schema.get("maximum").asLong() + 1),
              "maximum"));
    }
    if (value.isArray()) {
      ArrayNode array = (ArrayNode) value;
      if (schema.has("maxItems")) {
        out.add(
            replace(root, pointer, filled(array, schema.get("maxItems").asInt() + 1), "maxItems"));
      }
      if (schema.path("minItems").asInt() > 0) {
        out.add(
            replace(root, pointer, filled(array, schema.get("minItems").asInt() - 1), "minItems"));
      }
      if (schema.path("uniqueItems").asBoolean() && !array.isEmpty()) {
        ArrayNode duplicated = array.deepCopy();
        if (duplicated.size() > 1) {
          duplicated.set(1, duplicated.get(0).deepCopy());
        } else {
          duplicated.add(duplicated.get(0).deepCopy());
        }
        out.add(replace(root, pointer, duplicated, "uniqueItems"));
      }
      if (schema.has("items") && !array.isEmpty()) {
        mutate(spec, schema.get("items"), root, pointer + "/0", out);
      }
    }
    if (value.isObject()) {
      for (JsonNode required : schema.path("required")) {
        ObjectNode copy = root.deepCopy();
        JsonNode parent = pointer.isEmpty() ? copy : copy.at(pointer);
        ((ObjectNode) parent).remove(required.asText());
        out.add(new Mutation("required", pointer + "/" + required.asText(), copy));
      }
      Iterator<Map.Entry<String, JsonNode>> properties = schema.path("properties").fields();
      while (properties.hasNext()) {
        Map.Entry<String, JsonNode> property = properties.next();
        mutate(spec, property.getValue(), root, pointer + "/" + property.getKey(), out);
      }
    }
  }

  private ArrayNode filled(ArrayNode baseline, int size) {
    ArrayNode filled = json.createArrayNode();
    for (int i = 0; i < size; i++) {
      if (baseline.isEmpty() || baseline.get(0).isNumber()) {
        filled.add(i + 1);
      } else if (baseline.get(0).isObject()) {
        ObjectNode item = baseline.get(0).deepCopy();
        if (item.has("clientItemId")) {
          item.put("clientItemId", "item-" + i);
        }
        if (item.has("numbers")) {
          item.set("numbers", combination(i));
        }
        filled.add(item);
      } else if (baseline.get(0).isArray()) {
        filled.add(combination(i));
      } else {
        filled.add(baseline.get(0).deepCopy());
      }
    }
    return filled;
  }

  private ArrayNode combination(int index) {
    int seen = 0;
    for (int a = 5; a <= 44; a++) {
      for (int b = a + 1; b <= 45; b++) {
        if (seen++ == index) {
          return json.createArrayNode().add(1).add(2).add(3).add(4).add(a).add(b);
        }
      }
    }
    throw new IllegalArgumentException("index " + index);
  }

  private static Mutation replace(JsonNode root, String pointer, JsonNode value, String rule) {
    if (pointer.isEmpty()) {
      return new Mutation(rule, "/", value);
    }
    JsonNode copy = root.deepCopy();
    int cut = pointer.lastIndexOf('/');
    JsonNode parent = cut == 0 ? copy : copy.at(pointer.substring(0, cut));
    String last = pointer.substring(cut + 1);
    if (parent.isArray()) {
      ((ArrayNode) parent).set(Integer.parseInt(last), value);
    } else {
      ((ObjectNode) parent).set(last, value);
    }
    return new Mutation(rule, pointer, copy);
  }

  private static JsonNode resolve(JsonNode spec, JsonNode schema) {
    JsonNode current = schema;
    while (current.has("$ref")) {
      current = spec.at(current.get("$ref").asText().substring(1));
    }
    return current;
  }

  private JsonNode spec() throws IOException {
    LoaderOptions options = new LoaderOptions();
    options.setMaxAliasesForCollections(1_000);
    return json.valueToTree(new Yaml(options).load(Files.readString(OPENAPI)));
  }

  private Map<String, Long> stateCounts() {
    Map<String, Long> counts = new TreeMap<>();
    for (String table : STATE_TABLES) {
      counts.put(table, jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class));
    }
    return counts;
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
    String request =
        "RECOMMEND".equals(operation)
            ? "{\"operation\":\"RECOMMEND\",\"round\":" + ROUND + ",\"scheduleVersion\":\"1\"}"
            : "{\"operation\":\"" + operation + "\"}";
    MvcResult issued =
        mvc.perform(
                post("/api/v1/command-tokens")
                    .cookie(session.cookie())
                    .with(oauth2Login().oauth2User(member))
                    .header("X-CSRF-TOKEN", session.csrf())
                    .contentType("application/json")
                    .content(request))
            .andReturn();
    assertThat(issued.getResponse().getStatus()).as(operation).isEqualTo(200);
    return body(issued).get("token").asText();
  }

  private JsonNode body(MvcResult result) throws Exception {
    return json.readTree(result.getResponse().getContentAsString());
  }

  private record Target(
      HttpMethod method, String template, String url, String operation, String baseline) {}

  private record Mutation(String rule, String field, JsonNode body) {
    String description() {
      return rule + " " + field;
    }
  }

  private record Session(Cookie cookie, String csrf) {}
}
