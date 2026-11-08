package com.lottery.lottery_service.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.lottery.lottery_service.account.AccountStore;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;

class SessionRevocationFilterTest {

  private final AccountStore accounts = mock(AccountStore.class);
  private final MemberPrincipal principal =
      new MemberPrincipal(7, UUID.randomUUID(), 11, UUID.randomUUID(), 2, OAuthProvider.KAKAO);

  @AfterEach
  void clear() {
    SecurityContextHolder.clearContext();
  }

  @Test
  @DisplayName("버전이 바뀐 세션은 지우고 로그인하지 않은 요청으로 이어 간다")
  void revokedSessionCleared() throws Exception {
    when(accounts.sessionValid(7, 2)).thenReturn(false);
    MockHttpServletRequest request = authenticatedRequest();
    MockHttpSession session = (MockHttpSession) request.getSession();

    new SessionRevocationFilter(accounts)
        .doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    assertThat(session.isInvalid()).isTrue();
  }

  @Test
  @DisplayName("유효한 세션은 그대로 둔다")
  void validSessionKept() throws Exception {
    when(accounts.sessionValid(7, 2)).thenReturn(true);
    MockHttpServletRequest request = authenticatedRequest();
    MockHttpSession session = (MockHttpSession) request.getSession();

    new SessionRevocationFilter(accounts)
        .doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

    assertThat(SecurityContextHolder.getContext().getAuthentication().getPrincipal())
        .isEqualTo(principal);
    assertThat(session.isInvalid()).isFalse();
  }

  private MockHttpServletRequest authenticatedRequest() {
    SecurityContextHolder.getContext()
        .setAuthentication(
            new OAuth2AuthenticationToken(principal, principal.getAuthorities(), "kakao"));
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/session");
    request.setSession(new MockHttpSession());
    return request;
  }
}
