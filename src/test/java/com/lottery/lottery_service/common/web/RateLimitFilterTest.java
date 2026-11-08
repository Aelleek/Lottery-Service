package com.lottery.lottery_service.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.lottery.lottery_service.common.error.BusinessException;
import com.lottery.lottery_service.common.error.ErrorCode;
import com.lottery.lottery_service.support.MutableClock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.ModelAndView;

class RateLimitFilterTest {

  private static final Instant T0 = Instant.parse("2026-11-08T14:00:00Z");

  private final MutableClock clock = new MutableClock(T0);
  private final List<BusinessException> rejected = new ArrayList<>();
  private final RateLimitFilter filter =
      new RateLimitFilter(
          clock,
          (request, response, handler, ex) -> {
            rejected.add((BusinessException) ex);
            return new ModelAndView();
          });

  @Test
  @DisplayName("즉시 확인은 IP당 1분 30회, 31번째는 RATE_LIMITED와 창 끝 시각이고 다른 IP는 영향 없다")
  void checkLimit() throws Exception {
    assertThat(send("POST", "/api/v1/lotto/check", "10.0.0.1", 30)).isEqualTo(30);
    assertThat(send("POST", "/api/v1/lotto/check", "10.0.0.1", 1)).isZero();
    assertThat(rejected).hasSize(1);
    assertThat(rejected.get(0).getCode()).isEqualTo(ErrorCode.RATE_LIMITED);
    assertThat(rejected.get(0).getDetails().retryAt()).isEqualTo(T0.plusSeconds(60));
    assertThat(send("POST", "/api/v1/lotto/check", "10.0.0.2", 1)).isEqualTo(1);

    clock.set(T0.plusSeconds(60));
    assertThat(send("POST", "/api/v1/lotto/check", "10.0.0.1", 1)).isEqualTo(1);
  }

  @Test
  @DisplayName("회차 조회는 1분 120회, 로그인 시작은 1분 20회다")
  void lookupAndLoginLimits() throws Exception {
    assertThat(send("GET", "/api/v1/lotto/context", "10.0.0.1", 121)).isEqualTo(120);
    assertThat(send("GET", "/api/v1/lotto/rounds/1200/result", "10.0.0.3", 121)).isEqualTo(120);
    assertThat(send("GET", "/oauth2/authorization/kakao", "10.0.0.4", 21)).isEqualTo(20);
  }

  @Test
  @DisplayName("모든 API 합계는 1분 300회이고 X-Forwarded-For로 우회할 수 없다. 화면 파일은 세지 않는다")
  void totalLimit() throws Exception {
    int passed = 0;
    for (int i = 0; i < 301; i++) {
      MockHttpServletRequest request = request("GET", "/api/v1/session", "10.0.0.5");
      request.addHeader("X-Forwarded-For", "192.0.2." + i);
      MockFilterChain chain = new MockFilterChain();
      filter.doFilter(request, new MockHttpServletResponse(), chain);
      passed += chain.getRequest() == null ? 0 : 1;
    }
    assertThat(passed).isEqualTo(300);
    assertThat(send("GET", "/index.html", "10.0.0.5", 5)).isEqualTo(5);
  }

  private int send(String method, String path, String ip, int times) throws Exception {
    int passed = 0;
    for (int i = 0; i < times; i++) {
      MockFilterChain chain = new MockFilterChain();
      filter.doFilter(request(method, path, ip), new MockHttpServletResponse(), chain);
      passed += chain.getRequest() == null ? 0 : 1;
    }
    return passed;
  }

  private static MockHttpServletRequest request(String method, String path, String ip) {
    MockHttpServletRequest request = new MockHttpServletRequest(method, path);
    request.setRemoteAddr(ip);
    return request;
  }
}
