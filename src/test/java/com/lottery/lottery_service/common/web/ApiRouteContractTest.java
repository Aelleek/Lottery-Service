package com.lottery.lottery_service.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;

/** Checks that the served {@code /api/v1} routes are exactly the operations in openapi. */
@SpringBootTest(properties = "lottery.api-v1.enabled=true")
class ApiRouteContractTest {

  private static final Path OPENAPI = Path.of("docs/contracts/openapi.yaml");
  private static final String PREFIX = "/api/v1";
  private static final List<String> METHODS = List.of("get", "post", "put", "patch", "delete");

  @Autowired
  @Qualifier("requestMappingHandlerMapping")
  private RequestMappingHandlerMapping mapping;

  @Test
  @DisplayName("/api/v1 아래 컨트롤러 경로와 메서드가 openapi operation과 정확히 같다")
  void routesMatchOpenApi() throws IOException {
    assertThat(servedRoutes()).containsExactlyElementsOf(documentedRoutes());
  }

  private Set<String> servedRoutes() {
    Set<String> routes = new TreeSet<>();
    mapping
        .getHandlerMethods()
        .keySet()
        .forEach(
            info ->
                info.getPatternValues().stream()
                    .filter(p -> p.startsWith(PREFIX + "/"))
                    .forEach(
                        p ->
                            info.getMethodsCondition()
                                .getMethods()
                                .forEach(
                                    m ->
                                        routes.add(
                                            m.name() + " " + p.substring(PREFIX.length())))));
    return routes;
  }

  @SuppressWarnings("unchecked")
  private static Set<String> documentedRoutes() throws IOException {
    LoaderOptions options = new LoaderOptions();
    options.setMaxAliasesForCollections(1_000);
    Map<String, Object> spec = new Yaml(options).load(Files.readString(OPENAPI));
    Map<String, Map<String, Object>> paths = (Map<String, Map<String, Object>>) spec.get("paths");
    Set<String> routes = new TreeSet<>();
    paths.forEach(
        (path, operations) ->
            operations.keySet().stream()
                .filter(METHODS::contains)
                .forEach(m -> routes.add(m.toUpperCase(Locale.ROOT) + " " + path)));
    assertThat(routes).isNotEmpty();
    return routes;
  }
}
