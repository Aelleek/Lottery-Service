package com.lottery.lottery_service.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;

/** Checks that the served {@code /api/v1} routes and their parameters match openapi. */
@SpringBootTest(properties = "lottery.api-v1.enabled=true")
class ApiRouteContractTest {

  private static final Path OPENAPI = Path.of("docs/contracts/openapi.yaml");
  private static final String PREFIX = "/api/v1";
  private static final List<String> METHODS = List.of("get", "post", "put", "patch", "delete");
  private static final String CSRF_HEADER = "X-CSRF-TOKEN";

  @Autowired
  @Qualifier("requestMappingHandlerMapping")
  private RequestMappingHandlerMapping mapping;

  @Test
  @DisplayName("/api/v1 아래 컨트롤러 경로와 메서드가 openapi operation과 정확히 같다")
  void routesMatchOpenApi() throws IOException {
    assertThat(served().keySet()).containsExactlyElementsOf(documented().keySet());
  }

  @Test
  @DisplayName("operation마다 경로·조회 변수와 필수 헤더가 openapi와 같다(CSRF 헤더는 필터가 검사)")
  void parametersMatchOpenApi() throws IOException {
    Map<String, Set<String>> served = served();
    documented()
        .forEach(
            (route, params) ->
                assertThat(served.get(route)).as(route).containsExactlyElementsOf(params));
  }

  private Map<String, Set<String>> served() {
    Map<String, Set<String>> routes = new TreeMap<>();
    mapping
        .getHandlerMethods()
        .forEach(
            (info, handler) ->
                info.getPatternValues().stream()
                    .filter(p -> p.startsWith(PREFIX + "/"))
                    .forEach(
                        p ->
                            info.getMethodsCondition()
                                .getMethods()
                                .forEach(
                                    m ->
                                        routes.put(
                                            m.name() + " " + p.substring(PREFIX.length()),
                                            params(handler)))));
    return routes;
  }

  private static Set<String> params(HandlerMethod handler) {
    Set<String> params = new TreeSet<>();
    for (MethodParameter parameter : handler.getMethodParameters()) {
      PathVariable path = parameter.getParameterAnnotation(PathVariable.class);
      RequestParam query = parameter.getParameterAnnotation(RequestParam.class);
      RequestHeader header = parameter.getParameterAnnotation(RequestHeader.class);
      if (path != null) {
        params.add(describe("path", path.value(), path.required()));
      } else if (query != null) {
        params.add(describe("query", query.name(), query.required()));
      } else if (header != null) {
        params.add(describe("header", header.value(), header.required()));
      }
    }
    return params;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Set<String>> documented() throws IOException {
    LoaderOptions options = new LoaderOptions();
    options.setMaxAliasesForCollections(1_000);
    Map<String, Object> spec = new Yaml(options).load(Files.readString(OPENAPI));
    Map<String, Map<String, Object>> paths = (Map<String, Map<String, Object>>) spec.get("paths");
    Map<String, Map<String, Object>> shared =
        (Map<String, Map<String, Object>>)
            ((Map<String, Object>) spec.get("components")).get("parameters");
    Map<String, Set<String>> routes = new TreeMap<>();
    paths.forEach(
        (path, item) ->
            item.forEach(
                (method, operation) -> {
                  if (!METHODS.contains(method)) {
                    return;
                  }
                  List<Map<String, Object>> declared = new ArrayList<>();
                  declared.addAll(
                      (List<Map<String, Object>>) item.getOrDefault("parameters", List.of()));
                  declared.addAll(
                      (List<Map<String, Object>>)
                          ((Map<String, Object>) operation).getOrDefault("parameters", List.of()));
                  Set<String> params = new TreeSet<>();
                  for (Map<String, Object> parameter : declared) {
                    Map<String, Object> p = parameter;
                    if (p.containsKey("$ref")) {
                      String ref = (String) p.get("$ref");
                      p = shared.get(ref.substring(ref.lastIndexOf('/') + 1));
                    }
                    if (!CSRF_HEADER.equals(p.get("name"))) {
                      params.add(
                          describe(
                              (String) p.get("in"),
                              (String) p.get("name"),
                              Boolean.TRUE.equals(p.get("required"))));
                    }
                  }
                  routes.put(method.toUpperCase(Locale.ROOT) + " " + path, params);
                }));
    assertThat(routes).isNotEmpty();
    return routes;
  }

  private static String describe(String in, String name, boolean required) {
    return in + ":" + name + (required ? "" : "?");
  }
}
