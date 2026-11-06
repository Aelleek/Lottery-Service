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
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

class ApiExceptionHandlerTest {

  private static final Instant NOW = Instant.parse("2026-11-07T10:59:00Z");
  private static final String UUID_PATTERN =
      "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc =
        MockMvcBuilders.standaloneSetup(new ProbeController())
            .setControllerAdvice(new ApiExceptionHandler(Clock.fixed(NOW, ZoneOffset.UTC)))
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

  @RestController
  static class ProbeController {

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
