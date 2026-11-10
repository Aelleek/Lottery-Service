package com.lottery.lottery_service.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.lottery.lottery_service.account.AccountStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
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

  private static final Instant NOW = Instant.parse("2026-11-10T14:00:00Z");

  private final AccountStore accounts = mock(AccountStore.class);
  private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
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

    new SessionRevocationFilter(accounts, clock)
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

    new SessionRevocationFilter(accounts, clock)
        .doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

    assertThat(SecurityContextHolder.getContext().getAuthentication().getPrincipal())
        .isEqualTo(principal);
    assertThat(session.isInvalid()).isFalse();
  }

  @Test
  @DisplayName("로그인한 지 30일이 된 세션은 버전이 같아도 지우고, 30일이 안 됐으면 둔다(2026-11-09 Q5)")
  void absoluteLifetime() throws Exception {
    when(accounts.sessionValid(7, 2)).thenReturn(true);
    MockHttpServletRequest fresh = authenticatedRequest();
    SessionLifetime.markStarted(fresh.getSession(), NOW.minus(Duration.ofDays(30)).plusSeconds(1));
    new SessionRevocationFilter(accounts, clock)
        .doFilter(fresh, new MockHttpServletResponse(), new MockFilterChain());
    assertThat(((MockHttpSession) fresh.getSession(false)).isInvalid()).isFalse();
    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();

    MockHttpServletRequest old = authenticatedRequest();
    MockHttpSession session = (MockHttpSession) old.getSession();
    SessionLifetime.markStarted(session, NOW.minus(Duration.ofDays(30)));
    new SessionRevocationFilter(accounts, clock)
        .doFilter(old, new MockHttpServletResponse(), new MockFilterChain());
    assertThat(session.isInvalid()).isTrue();
    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
  }

  @Test
  @DisplayName("로그인 시각이 없는 세션은 세션을 만든 시각으로 30일을 센다")
  void lifetimeFallsBackToCreationTime() {
    MockHttpSession session = new MockHttpSession();
    assertThat(SessionLifetime.expired(session, Instant.ofEpochMilli(session.getCreationTime())))
        .isFalse();
    assertThat(
            SessionLifetime.expired(
                session, Instant.ofEpochMilli(session.getCreationTime()).plus(SessionLifetime.MAX)))
        .isTrue();
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
