package com.lottery.lottery_service.common.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lottery.lottery_service.common.number.LottoNumbers;
import com.lottery.lottery_service.common.web.RequestIdFilter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(OutputCaptureExtension.class)
class ApiExceptionHandlerTest {

  private static final Instant NOW = Instant.parse("2026-11-07T10:59:00Z");
  private static final String UUID_PATTERN =
      "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

  private MockMvc mockMvc;
  private final com.lottery.lottery_service.common.web.SecuritySignals signals =
      new com.lottery.lottery_service.common.web.SecuritySignals();

  @BeforeEach
  void setUp() {
    mockMvc =
        MockMvcBuilders.standaloneSetup(new ProbeController())
            .setControllerAdvice(new ApiExceptionHandler(Clock.fixed(NOW, ZoneOffset.UTC), signals))
            .addFilters(new RequestIdFilter())
            .build();
  }

  @Test
  @DisplayName("업무 예외는 코드의 상태와 code, message, retryable, requestId, details로 응답한다")
  void businessExceptionHasContractShape() throws Exception {
    MvcResult result =
        mockMvc
            .perform(get("/probe/business").param("code", "ROUND_CHANGED"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("ROUND_CHANGED"))
            .andExpect(jsonPath("$.message").value("회차 정보를 새로 확인해 주세요."))
            .andExpect(jsonPath("$.retryable").value(false))
            .andExpect(jsonPath("$.requestId").value(matchesPattern(UUID_PATTERN)))
            .andExpect(jsonPath("$.details").isMap())
            .andExpect(jsonPath("$.details.retryAt").doesNotExist())
            .andExpect(header().doesNotExist("Retry-After"))
            .andReturn();

    assertThat(result.getResponse().getHeader(RequestIdFilter.HEADER))
        .isEqualTo(
            com.jayway.jsonpath.JsonPath.read(
                result.getResponse().getContentAsString(), "$.requestId"));
  }

  @Test
  @DisplayName("429에 재시도 시각이 있으면 Retry-After 초를 올림해서 준다")
  void retryAfterForTooManyRequests() throws Exception {
    mockMvc
        .perform(get("/probe/busy").param("retryAt", "2026-11-07T10:59:30.200Z"))
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.code").value("GENERATION_BUSY"))
        .andExpect(jsonPath("$.retryable").value(true))
        .andExpect(jsonPath("$.details.retryAt").value("2026-11-07T10:59:30.200Z"))
        .andExpect(header().string("Retry-After", "31"));
    assertThat(signals.drain())
        .containsOnlyKeys(
            com.lottery.lottery_service.common.web.SecuritySignals.Kind.TOO_MANY_REQUESTS);
  }

  @Test
  @DisplayName("출처 검사 거절과 CSRF 거절은 보안 신호로 세고, 다른 업무 오류는 세지 않는다")
  void recordsSecuritySignals() throws Exception {
    for (String code : new String[] {"ORIGIN_REJECTED", "CSRF_REJECTED", "ROUND_CHANGED"}) {
      mockMvc.perform(get("/probe/business").param("code", code));
    }

    assertThat(signals.drain())
        .containsOnlyKeys(
            com.lottery.lottery_service.common.web.SecuritySignals.Kind.ORIGIN_REJECTED,
            com.lottery.lottery_service.common.web.SecuritySignals.Kind.CSRF_REJECTED);
  }

  @Test
  @DisplayName("503에 재시도 시각이 있으면 Retry-After를 준다")
  void retryAfterForServiceUnavailable() throws Exception {
    mockMvc
        .perform(get("/probe/unavailable").param("retryAt", "2026-11-07T11:00:00Z"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value("TEMPORARILY_UNAVAILABLE"))
        .andExpect(jsonPath("$.retryable").value(true))
        .andExpect(jsonPath("$.details.retryAt").value("2026-11-07T11:00:00Z"))
        .andExpect(header().string("Retry-After", "60"));
  }

  @Test
  @DisplayName("재시도 시각이 없는 503은 Retry-After 없이 빈 details로 답한다")
  void serviceUnavailableWithoutRetryAt() throws Exception {
    mockMvc
        .perform(get("/probe/unavailable"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value("TEMPORARILY_UNAVAILABLE"))
        .andExpect(jsonPath("$.details").isMap())
        .andExpect(jsonPath("$.details.retryAt").doesNotExist())
        .andExpect(header().doesNotExist("Retry-After"));
  }

  @Test
  @DisplayName("재시도 시각 없이 details를 만들 수 없다")
  void retryAtRequiresInstant() {
    assertThatThrownBy(() -> ErrorDetails.retryAt(null)).isInstanceOf(NullPointerException.class);
  }

  @Test
  @DisplayName("이미 지난 재시도 시각이면 Retry-After는 0이다")
  void retryAfterNeverNegative() throws Exception {
    mockMvc
        .perform(get("/probe/busy").param("retryAt", "2026-11-07T10:58:00Z"))
        .andExpect(status().isTooManyRequests())
        .andExpect(header().string("Retry-After", "0"));
  }

  @Test
  @DisplayName("버전 충돌은 currentVersion을 십진 문자열로 준다")
  void versionConflictUsesDecimalString() throws Exception {
    mockMvc
        .perform(get("/probe/version"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"))
        .andExpect(jsonPath("$.details.currentVersion").value("9007199254740993"));
  }

  @Test
  @DisplayName("DB 예외는 500이고, 로그에는 종류와 SQL 상태만 남고 메시지와 원인 체인의 값은 남지 않는다(2026-11-09 Q10)")
  void databaseErrorsLogOnlyTypeAndSqlState(CapturedOutput output) throws Exception {
    mockMvc
        .perform(get("/probe/db"))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
    mockMvc.perform(get("/probe/wrapped-sql")).andExpect(status().isInternalServerError());
    mockMvc.perform(get("/probe/empty-result")).andExpect(status().isInternalServerError());

    assertThat(output.getOut() + output.getErr())
        .contains("type=DataIntegrityViolationException sqlState=23000 errorCode=1062")
        .contains("type=IllegalStateException sqlState=22001 errorCode=1406")
        .contains("type=EmptyResultDataAccessException sqlState=null errorCode=null")
        .doesNotContain("01 02 03 04 05 06")
        .doesNotContain("010-5821-9734")
        .doesNotContain("SQLIntegrityConstraintViolationException");
  }

  @Test
  @DisplayName("본문 검증 실패는 400 VALIDATION_FAILED와 위치·제약 이름만 준다")
  void invalidBody() throws Exception {
    mockMvc
        .perform(
            post("/probe/body").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        .andExpect(jsonPath("$.retryable").value(false))
        .andExpect(jsonPath("$.details.fieldErrors[0].path").value("name"))
        .andExpect(jsonPath("$.details.fieldErrors[0].reason").value("NotBlank"));
  }

  @Test
  @DisplayName("깨진 JSON은 400 VALIDATION_FAILED다")
  void unreadableBody() throws Exception {
    mockMvc
        .perform(post("/probe/body").contentType(MediaType.APPLICATION_JSON).content("{"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        .andExpect(jsonPath("$.details").isMap());
  }

  @Test
  @DisplayName("번호 규칙 위반은 422 VALIDATION_FAILED이고 입력 번호를 응답에 싣지 않는다")
  void invalidNumbersAreNotEchoed() throws Exception {
    MvcResult result =
        mockMvc
            .perform(get("/probe/numbers").param("values", "1,2,3,4,5,46"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.message").value(ApiExceptionHandler.NUMBERS_MESSAGE))
            .andReturn();

    assertThat(result.getResponse().getContentAsString())
        .doesNotContain(": 46")
        .doesNotContain("[1,2,3,4,5,46]");
  }

  @Test
  @DisplayName("요청마다 다른 요청 ID를 만들고, 들어온 X-Request-Id는 쓰지 않는다")
  void requestIdIsGeneratedPerRequest() throws Exception {
    String first =
        mockMvc
            .perform(get("/probe/business").param("code", "NOT_FOUND").header("X-Request-Id", "x"))
            .andReturn()
            .getResponse()
            .getHeader(RequestIdFilter.HEADER);
    String second =
        mockMvc
            .perform(get("/probe/business").param("code", "NOT_FOUND"))
            .andReturn()
            .getResponse()
            .getHeader(RequestIdFilter.HEADER);

    assertThat(first).matches(UUID_PATTERN).isNotEqualTo(second);
  }

  @Test
  @DisplayName("처리하지 않던 예외는 500 INTERNAL_ERROR, retryable=false로 답하고 예외 메시지를 싣지 않는다")
  void unexpectedExceptionIsInternalError() throws Exception {
    mockMvc
        .perform(get("/probe/boom"))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
        .andExpect(jsonPath("$.message").value(ApiExceptionHandler.INTERNAL_MESSAGE))
        .andExpect(jsonPath("$.retryable").value(false))
        .andExpect(jsonPath("$.requestId").value(matchesPattern(UUID_PATTERN)))
        .andExpect(jsonPath("$.details").isMap())
        .andExpect(header().doesNotExist("Retry-After"));
  }

  @Test
  @DisplayName("빠진 파라미터와 허용되지 않은 메서드는 500이 아니라 그 4xx 상태와 VALIDATION_FAILED로 답한다")
  void springClientErrorsKeepTheirStatus() throws Exception {
    mockMvc
        .perform(get("/probe/busy"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    mockMvc
        .perform(post("/probe/boom"))
        .andExpect(status().isMethodNotAllowed())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        .andExpect(jsonPath("$.retryable").value(false));
  }

  @Test
  @DisplayName("없는 경로와 404로 정한 예외는 NOT_FOUND로 답한다")
  void notFound() throws Exception {
    mockMvc
        .perform(get("/probe/nowhere"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    mockMvc
        .perform(get("/probe/gone"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"))
        .andExpect(jsonPath("$.message").value(ApiExceptionHandler.NOT_FOUND_MESSAGE));
  }

  @Test
  @DisplayName("인가 예외는 형식을 바꾸지 않고 Spring Security에 넘긴다")
  void accessDeniedIsRethrown() {
    assertThatThrownBy(() -> mockMvc.perform(get("/probe/denied")))
        .hasCauseInstanceOf(AccessDeniedException.class);
  }

  @Test
  @DisplayName("401·403으로 정한 예외는 UNAUTHENTICATED·FORBIDDEN과 그 메시지로 답한다")
  void declaredUnauthorizedAndForbidden() throws Exception {
    mockMvc
        .perform(get("/probe/unauthorized"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
        .andExpect(jsonPath("$.message").value(ApiExceptionHandler.UNAUTHENTICATED_MESSAGE));
    mockMvc
        .perform(get("/probe/annotated-forbidden"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"))
        .andExpect(jsonPath("$.message").value(ApiExceptionHandler.FORBIDDEN_MESSAGE));
  }

  @Test
  @DisplayName("@ResponseStatus만 붙은 예외도 그 상태로 답한다")
  void responseStatusAnnotationFallback() throws Exception {
    mockMvc
        .perform(get("/probe/annotated-conflict"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
  }

  @Test
  @DisplayName("인증 예외도 형식을 바꾸지 않고 Spring Security에 넘긴다")
  void authenticationExceptionIsRethrown() {
    assertThatThrownBy(() -> mockMvc.perform(get("/probe/bad-credentials")))
        .hasCauseInstanceOf(BadCredentialsException.class);
  }

  @ResponseStatus(HttpStatus.FORBIDDEN)
  static class AnnotatedForbiddenException extends RuntimeException {}

  @ResponseStatus(HttpStatus.CONFLICT)
  static class AnnotatedConflictException extends RuntimeException {}

  @RestController
  static class ProbeController {

    @GetMapping("/probe/unauthorized")
    String unauthorized() {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    }

    @GetMapping("/probe/annotated-forbidden")
    String annotatedForbidden() {
      throw new AnnotatedForbiddenException();
    }

    @GetMapping("/probe/annotated-conflict")
    String annotatedConflict() {
      throw new AnnotatedConflictException();
    }

    @GetMapping("/probe/bad-credentials")
    String badCredentials() {
      throw new BadCredentialsException("bad");
    }

    @GetMapping("/probe/db")
    String db() {
      throw new DataIntegrityViolationException(
          "Duplicate entry '01 02 03 04 05 06' for key 'uk_record'",
          new SQLIntegrityConstraintViolationException(
              "Duplicate entry '01 02 03 04 05 06' for key 'uk_record'", "23000", 1062));
    }

    @GetMapping("/probe/empty-result")
    String emptyResult() {
      throw new org.springframework.dao.EmptyResultDataAccessException(
          "no row for 010-5821-9734", 1);
    }

    @GetMapping("/probe/wrapped-sql")
    String wrappedSql() {
      throw new IllegalStateException(
          "wrapped 010-5821-9734", new SQLException("value 010-5821-9734", "22001", 1406));
    }

    @GetMapping("/probe/boom")
    String boom() {
      throw new IllegalStateException("internal detail 1 2 3 4 5 6");
    }

    @GetMapping("/probe/gone")
    String gone() {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "internal detail");
    }

    @GetMapping("/probe/denied")
    String denied() {
      throw new AccessDeniedException("denied");
    }

    @GetMapping("/probe/business")
    String business(@RequestParam ErrorCode code) {
      throw new BusinessException(code, "회차 정보를 새로 확인해 주세요.");
    }

    @GetMapping("/probe/busy")
    String busy(@RequestParam String retryAt) {
      throw new BusinessException(
          ErrorCode.GENERATION_BUSY,
          "잠시 뒤 다시 시도해 주세요.",
          ErrorDetails.retryAt(Instant.parse(retryAt)));
    }

    @GetMapping("/probe/unavailable")
    String unavailable(@RequestParam(required = false) String retryAt) {
      ErrorDetails details =
          retryAt == null ? ErrorDetails.empty() : ErrorDetails.retryAt(Instant.parse(retryAt));
      throw new BusinessException(ErrorCode.TEMPORARILY_UNAVAILABLE, "잠시 뒤 다시 시도해 주세요.", details);
    }

    @GetMapping("/probe/version")
    String version() {
      throw new BusinessException(
          ErrorCode.VERSION_CONFLICT,
          "다시 확인해 주세요.",
          ErrorDetails.currentVersion(9007199254740993L));
    }

    @PostMapping("/probe/body")
    String body(@Valid @RequestBody Body body) {
      return body.name();
    }

    @GetMapping("/probe/numbers")
    String numbers(@RequestParam List<Integer> values) {
      return LottoNumbers.of(values).key();
    }
  }

  record Body(@NotBlank String name) {}
}
