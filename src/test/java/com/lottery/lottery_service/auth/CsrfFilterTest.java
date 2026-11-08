package com.lottery.lottery_service.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.lottery.lottery_service.common.error.BusinessException;
import com.lottery.lottery_service.common.error.ErrorCode;
import com.lottery.lottery_service.common.web.OriginGuard;
import com.lottery.lottery_service.common.web.OriginProperties;
import com.lottery.lottery_service.support.MutableClock;
import com.lottery.lottery_service.support.TestSecrets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;
import org.springframework.web.servlet.ModelAndView;

class CsrfFilterTest {

  private final MutableClock clock = new MutableClock(Instant.parse("2026-11-08T15:00:00Z"));
  private final CsrfTokens tokens = new CsrfTokens(TestSecrets.keys(), clock);
  private final List<ErrorCode> rejected = new ArrayList<>();
  private final CsrfFilter filter =
      new CsrfFilter(
          tokens,
          new OriginGuard(new OriginProperties(null)),
          (request, response, handler, ex) -> {
            rejected.add(((BusinessException) ex).getCode());
            return new ModelAndView();
          });
  private final GuestPrincipal guest =
      new GuestPrincipal(1, UUID.randomUUID(), Instant.parse("2026-12-15T00:00:00Z"));

  @AfterEach
  void clear() {
    SecurityContextHolder.clearContext();
  }

  @Test
  @DisplayName("읽기 요청과 즉시 확인은 검사하지 않고, 자격 발급은 출처만 본다")
  void exemptions() throws Exception {
    assertThat(pass(request("GET", "/api/v1/session"))).isTrue();
    assertThat(pass(request("POST", "/api/v1/lotto/check"))).isTrue();
    assertThat(pass(request("POST", "/api/v1/guest-credentials"))).isTrue();
    MockHttpServletRequest crossSite = request("POST", "/api/v1/guest-credentials");
    crossSite.addHeader("Sec-Fetch-Site", "cross-site");
    assertThat(pass(crossSite)).isFalse();
    assertThat(rejected).containsExactly(ErrorCode.ORIGIN_REJECTED);
  }

  @Test
  @DisplayName("주체가 있는 변경 요청은 그 주체에 묶은 토큰이 있어야 하고, 주체가 없으면 권한 검사에 맡긴다")
  void tokenRequired() throws Exception {
    assertThat(pass(request("POST", "/api/v1/command-tokens"))).isTrue();

    SecurityContextHolder.getContext()
        .setAuthentication(new PreAuthenticatedAuthenticationToken(guest, null, List.of()));
    assertThat(pass(request("POST", "/api/v1/command-tokens"))).isFalse();
    MockHttpServletRequest withToken = request("POST", "/api/v1/command-tokens");
    withToken.addHeader(CsrfFilter.HEADER, tokens.issue(guest));
    assertThat(pass(withToken)).isTrue();
    MockHttpServletRequest logout = request("POST", "/logout");
    assertThat(pass(logout)).isFalse();
    assertThat(rejected).containsExactly(ErrorCode.CSRF_REJECTED, ErrorCode.CSRF_REJECTED);
  }

  private boolean pass(MockHttpServletRequest request) throws Exception {
    MockFilterChain chain = new MockFilterChain();
    filter.doFilter(request, new MockHttpServletResponse(), chain);
    return chain.getRequest() != null;
  }

  private static MockHttpServletRequest request(String method, String path) {
    return new MockHttpServletRequest(method, path);
  }
}
