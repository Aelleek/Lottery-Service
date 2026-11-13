package com.lottery.lottery_service.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcBuilderCustomizer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultHandler;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;

/**
 * MockMvc 응답이 openapi 계약({@code docs/contracts/openapi.yaml})과 맞는지 확인한다. {@code /api/v1} 응답마다 그
 * operation에 상태 코드가 적혀 있는지, JSON 본문이 그 응답 스키마를 따르는지 본다. 테스트에
 * {@code @Import(OpenApiContract.Config.class)}로 붙인다.
 */
public final class OpenApiContract implements ResultHandler {

  private static final String PREFIX = "/api/v1";
  private static final String BASE = "https://contract.invalid/openapi.json";
  private static final Path OPENAPI = Path.of("docs/contracts/openapi.yaml");
  private static final Set<String> METHODS = Set.of("get", "post", "put", "patch", "delete");
  private static final Pattern LEADING_STATUSES = Pattern.compile("^(\\d{3}(?:·\\d{3})*)");
  private static final String OTHER_FORMAT_ERRORS = "VALIDATION_FAILED";
  private static final Set<String> TOKEN_ROUTES = Set.of("/session", "/guest-credentials");

  private final JsonNode spec;
  private final JsonSchemaFactory factory;
  private final boolean securityHeaders;
  private final List<Route> routes = new ArrayList<>();
  private final Map<String, JsonSchema> schemas = new ConcurrentHashMap<>();
  private final ObjectMapper json = new ObjectMapper();
  private final Map<String, Set<Integer>> codeStatuses = new ConcurrentHashMap<>();

  /** openapi 파일을 읽어 검사기를 만든다. 보안 필터가 없는 standalone MockMvc용이라 응답 헤더는 보지 않는다. */
  public OpenApiContract() {
    this(false);
  }

  /**
   * openapi 파일을 읽어 검사기를 만든다.
   *
   * @param securityHeaders 보안 필터까지 거친 응답이면 true. 주체가 필요한 operation과 토큰을 주는 operation의 응답에 {@code
   *     Cache-Control: no-store}가 있는지도 본다(03 6.2)
   */
  public OpenApiContract(boolean securityHeaders) {
    this.securityHeaders = securityHeaders;
    try {
      LoaderOptions options = new LoaderOptions();
      options.setMaxAliasesForCollections(1_000);
      Object loaded = new Yaml(options).load(Files.readString(OPENAPI, StandardCharsets.UTF_8));
      spec = json.valueToTree(loaded);
      String document = json.writeValueAsString(spec);
      factory =
          JsonSchemaFactory.getInstance(
              SpecVersion.VersionFlag.V202012,
              b -> b.schemaLoaders(l -> l.schemas(Map.of(BASE, document))));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    Iterator<Map.Entry<String, JsonNode>> paths = spec.path("paths").fields();
    while (paths.hasNext()) {
      Map.Entry<String, JsonNode> path = paths.next();
      Pattern pattern = Pattern.compile(path.getKey().replaceAll("\\{[^/]+}", "[^/]+"));
      routes.add(new Route(path.getKey(), pattern, path.getValue()));
    }
    for (JsonNode code : spec.at("/components/schemas/Error/properties/code/oneOf")) {
      Matcher m = LEADING_STATUSES.matcher(code.path("description").asText());
      Set<Integer> statuses = new HashSet<>();
      if (m.find()) {
        for (String value : m.group(1).split("·")) {
          statuses.add(Integer.parseInt(value));
        }
      }
      codeStatuses.put(code.path("const").asText(), statuses);
    }
  }

  @Override
  public void handle(MvcResult result) throws Exception {
    MockHttpServletRequest request = result.getRequest();
    String uri = request.getRequestURI();
    if (!uri.startsWith(PREFIX + "/")) {
      return;
    }
    String path = uri.substring(PREFIX.length());
    String method = request.getMethod().toLowerCase(Locale.ROOT);
    Route route =
        routes.stream()
            .filter(r -> r.pattern().matcher(path).matches() && r.item().has(method))
            .findFirst()
            .orElse(null);
    if (route == null || !METHODS.contains(method)) {
      return;
    }
    MockHttpServletResponse response = result.getResponse();
    String operation = method.toUpperCase(Locale.ROOT) + " " + route.template();
    if (securityHeaders && personal(route, method)) {
      String cache = String.valueOf(response.getHeader("Cache-Control"));
      if (!cache.contains("no-store")) {
        throw new AssertionError(
            operation + " 응답 " + response.getStatus() + "에 Cache-Control no-store가 없다: " + cache);
      }
    }
    String status = Integer.toString(response.getStatus());
    String pointer = "/paths/" + escape(route.template()) + "/" + method + "/responses/";
    JsonNode responses = route.item().path(method).path("responses");
    if (responses.has(status)) {
      pointer += status;
    } else if (responses.has("default")) {
      pointer += "default";
    } else {
      throw new AssertionError(operation + " 응답 " + status + "이(가) openapi에 없다");
    }
    JsonNode declared = spec.at(pointer);
    if (declared.has("$ref")) {
      pointer = declared.get("$ref").asText().substring(1);
      declared = spec.at(pointer);
    }
    JsonNode content = declared.path("content").path("application/json");
    String body = response.getContentAsString(StandardCharsets.UTF_8);
    if (content.isMissingNode()) {
      return;
    }
    if (body.isEmpty()) {
      throw new AssertionError(operation + " 응답 " + status + "에 JSON 본문이 없다");
    }
    validate(pointer, body, operation + " 응답 " + status);
    checkErrorCode(json.readTree(body), response.getStatus(), operation);
    String requestBody =
        request.getContentAsByteArray() == null
            ? ""
            : new String(request.getContentAsByteArray(), StandardCharsets.UTF_8);
    String requestPointer = "/paths/" + escape(route.template()) + "/" + method + "/requestBody";
    if (response.getStatus() < 300
        && !requestBody.isBlank()
        && !spec.at(requestPointer + "/content/" + escape("application/json")).isMissingNode()) {
      validate(requestPointer, requestBody, operation + " 요청(" + status + "으로 받아들임)");
    }
  }

  private boolean personal(Route route, String method) {
    JsonNode security = route.item().path(method).path("security");
    if (security.isMissingNode()) {
      security = spec.path("security");
    }
    return !security.isEmpty() || TOKEN_ROUTES.contains(route.template());
  }

  private void checkErrorCode(JsonNode body, int status, String operation) {
    if (status < 400 || !body.path("code").isTextual()) {
      return;
    }
    String code = body.path("code").asText();
    Set<Integer> allowed = codeStatuses.getOrDefault(code, Set.of());
    boolean otherFormat = OTHER_FORMAT_ERRORS.equals(code) && status < 500;
    if (!allowed.contains(status) && !otherFormat) {
      throw new AssertionError(
          operation + " 응답 " + status + "의 오류 코드 " + code + "는 openapi에서 " + allowed + " 상태로만 쓴다");
    }
  }

  private void validate(String pointer, String body, String label) throws IOException {
    String schemaPointer = pointer + "/content/" + escape("application/json") + "/schema";
    JsonSchema schema =
        schemas.computeIfAbsent(
            schemaPointer, p -> factory.getSchema(SchemaLocation.of(BASE + "#" + p)));
    Set<ValidationMessage> errors = schema.validate(json.readTree(body));
    if (!errors.isEmpty()) {
      throw new AssertionError(
          label
              + "이(가) openapi 스키마와 다르다: "
              + errors.stream()
                  .map(ValidationMessage::getMessage)
                  .collect(Collectors.joining("; ")));
    }
  }

  private static String escape(String token) {
    return token.replace("~", "~0").replace("/", "~1");
  }

  private record Route(String template, Pattern pattern, JsonNode item) {}

  /** 모든 MockMvc 요청 뒤에 계약 검사를 붙이는 설정. */
  @TestConfiguration(proxyBeanMethods = false)
  public static class Config {

    /** MockMvc에 계약 검사를 붙인다. */
    @Bean
    MockMvcBuilderCustomizer openApiContract() {
      OpenApiContract contract = new OpenApiContract(true);
      return builder -> builder.alwaysDo(contract);
    }
  }
}
