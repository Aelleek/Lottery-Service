package com.lottery.lottery_service.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lottery.lottery_service.common.error.BusinessException;
import com.lottery.lottery_service.common.error.ErrorCode;
import java.util.List;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class OriginGuardTest {

  private final OriginGuard self = new OriginGuard(new OriginProperties(null));
  private final OriginGuard configured =
      new OriginGuard(new OriginProperties(List.of("https://lotto.example.com")));

  @Test
  @DisplayName("설정이 없으면 서버 자신의 출처만 받고, 기본 포트 표기와 끝 슬래시는 같은 출처로 본다")
  void selfOrigin() {
    assertThatCode(
            () ->
                self.check(
                    post("https", "lotto.example.com", 443, "https://lotto.example.com", null)))
        .doesNotThrowAnyException();
    assertThatCode(
            () ->
                self.check(
                    post(
                        "https", "lotto.example.com", 443, "https://lotto.example.com:443/", null)))
        .doesNotThrowAnyException();
    assertThatCode(
            () ->
                self.check(post("http", "localhost", 8080, "http://localhost:8080", "same-origin")))
        .doesNotThrowAnyException();
    assertRejected(
        () -> self.check(post("http", "localhost", 8080, "http://localhost:3000", null)));
    assertRejected(() -> self.check(post("https", "lotto.example.com", 443, "null", null)));
  }

  @Test
  @DisplayName("설정한 출처만 받고, Sec-Fetch-Site가 cross-site·same-site면 막으며, 둘 다 없거나 읽기 요청은 통과한다")
  void configuredOriginsAndFetchMetadata() {
    assertThatCode(
            () ->
                configured.check(
                    post("http", "internal", 8080, "https://lotto.example.com", "same-origin")))
        .doesNotThrowAnyException();
    assertRejected(
        () -> configured.check(post("http", "internal", 8080, "https://evil.example.com", null)));
    assertRejected(() -> configured.check(post("http", "internal", 8080, null, "cross-site")));
    assertRejected(() -> configured.check(post("http", "internal", 8080, null, "same-site")));
    assertThatCode(() -> configured.check(post("http", "internal", 8080, null, null)))
        .doesNotThrowAnyException();
    assertThatCode(() -> configured.check(post("http", "internal", 8080, null, "none")))
        .doesNotThrowAnyException();
    MockHttpServletRequest get =
        post("http", "internal", 8080, "https://evil.example.com", "cross-site");
    get.setMethod("GET");
    assertThatCode(() -> configured.check(get)).doesNotThrowAnyException();
  }

  private static MockHttpServletRequest post(
      String scheme, String host, int port, String origin, String fetchSite) {
    MockHttpServletRequest request =
        new MockHttpServletRequest("POST", "/api/v1/guest-credentials");
    request.setScheme(scheme);
    request.setServerName(host);
    request.setServerPort(port);
    if (origin != null) {
      request.addHeader("Origin", origin);
    }
    if (fetchSite != null) {
      request.addHeader("Sec-Fetch-Site", fetchSite);
    }
    return request;
  }

  private static void assertRejected(ThrowingCallable call) {
    assertThatThrownBy(call)
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.getCode()).isEqualTo(ErrorCode.ORIGIN_REJECTED));
  }
}
