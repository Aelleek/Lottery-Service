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
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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

  private final JsonNode spec;
  private final JsonSchemaFactory factory;
  private final List<Route> routes = new ArrayList<>();
  private final Map<String, JsonSchema> schemas = new ConcurrentHashMap<>();
  private final ObjectMapper json = new ObjectMapper();

  /** openapi 파일을 읽어 검사기를 만든다. */
  public OpenApiContract() {
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
    String schemaPointer = pointer + "/content/" + escape("application/json") + "/schema";
    JsonSchema schema =
        schemas.computeIfAbsent(
            schemaPointer, p -> factory.getSchema(SchemaLocation.of(BASE + "#" + p)));
    Set<ValidationMessage> errors = schema.validate(json.readTree(body));
    if (!errors.isEmpty()) {
      throw new AssertionError(
          operation
              + " 응답 "
              + status
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
      OpenApiContract contract = new OpenApiContract();
      return builder -> builder.alwaysDo(contract);
    }
  }
}
