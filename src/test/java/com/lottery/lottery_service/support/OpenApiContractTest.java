package com.lottery.lottery_service.support;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MvcResult;

class OpenApiContractTest {

  private static final OpenApiContract CONTRACT = new OpenApiContract();
  private static final String SESSION_NONE =
      "{\"kind\":\"NONE\",\"csrfToken\":null,\"guestExpiresAt\":null,"
          + "\"serverTime\":\"2026-11-12T14:00:00Z\"}";
  private static final String UNAUTHENTICATED =
      "{\"code\":\"UNAUTHENTICATED\",\"message\":\"로그인이 필요합니다.\",\"retryable\":false,"
          + "\"requestId\":\"6f1c2a52-6a0e-4c43-9b8f-0a1d2e3f4a5b\",\"details\":{}}";

  @Test
  @DisplayName("적힌 상태와 스키마를 따르는 응답은 통과한다")
  void documentedResponsePasses() {
    assertThatCode(() -> CONTRACT.handle(result("GET", "/api/v1/session", 200, SESSION_NONE)))
        .doesNotThrowAnyException();
    assertThatCode(
            () -> CONTRACT.handle(result("GET", "/api/v1/me/lotto/records", 401, UNAUTHENTICATED)))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("필수 필드가 빠지거나 스키마에 없는 필드가 있으면 실패한다")
  void schemaMismatchFails() {
    assertThatThrownBy(
            () ->
                CONTRACT.handle(
                    result(
                        "GET", "/api/v1/session", 200, "{\"kind\":\"NONE\",\"csrfToken\":null}")))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("GET /session");
    assertThatThrownBy(
            () ->
                CONTRACT.handle(
                    result(
                        "GET",
                        "/api/v1/session",
                        200,
                        SESSION_NONE.replace("}", ",\"memberId\":1}"))))
        .isInstanceOf(AssertionError.class);
    assertThatThrownBy(
            () ->
                CONTRACT.handle(
                    result(
                        "GET",
                        "/api/v1/me/lotto/records",
                        401,
                        UNAUTHENTICATED.replace("UNAUTHENTICATED", "NO_SUCH"))))
        .isInstanceOf(AssertionError.class);
  }

  @Test
  @DisplayName(
      "openapi에 없는 상태 코드는 실패하고, openapi에 없는 경로와 /api/v1 밖의 요청은 보지 않는다(경로는 ApiRouteContractTest)")
  void undocumentedStatusFails() {
    assertThatThrownBy(() -> CONTRACT.handle(result("GET", "/api/v1/session", 418, "{}")))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("418");
    assertThatCode(() -> CONTRACT.handle(result("GET", "/api/v1/nothing", 404, "{}")))
        .doesNotThrowAnyException();
    assertThatCode(() -> CONTRACT.handle(result("GET", "/h2-console", 404, "")))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("서버가 2xx로 받아들인 요청은 본문이 요청 스키마를 따라야 하고, 거절한 요청은 보지 않는다")
  void acceptedRequestBodyChecked() {
    String token = "{\"token\":\"t\",\"expiresAt\":\"2026-11-13T14:00:00Z\"}";
    assertThatCode(
            () ->
                CONTRACT.handle(
                    result(
                        "POST",
                        "/api/v1/command-tokens",
                        "{\"operation\":\"WITHDRAW\"}",
                        200,
                        token)))
        .doesNotThrowAnyException();
    assertThatThrownBy(
            () ->
                CONTRACT.handle(
                    result(
                        "POST", "/api/v1/command-tokens", "{\"operation\":\"NOPE\"}", 200, token)))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("요청");
    assertThatCode(
            () ->
                CONTRACT.handle(
                    result(
                        "POST",
                        "/api/v1/command-tokens",
                        "{\"operation\":\"NOPE\"}",
                        400,
                        UNAUTHENTICATED.replace("UNAUTHENTICATED", "VALIDATION_FAILED"))))
        .doesNotThrowAnyException();
  }

  private static MvcResult result(String method, String uri, int status, String body)
      throws Exception {
    return result(method, uri, "", status, body);
  }

  private static MvcResult result(
      String method, String uri, String requestBody, int status, String body) throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
    request.setContent(requestBody.getBytes(StandardCharsets.UTF_8));
    MockHttpServletResponse response = new MockHttpServletResponse();
    response.setStatus(status);
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    response.getWriter().write(body);
    MvcResult result = mock(MvcResult.class);
    when(result.getRequest()).thenReturn(request);
    when(result.getResponse()).thenReturn(response);
    return result;
  }
}
