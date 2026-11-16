package com.lottery.lottery_service.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lottery.lottery_service.account.AccountStore;
import com.lottery.lottery_service.account.MemberLogin;
import com.lottery.lottery_service.auth.extractor.KakaoUserInfoExtractor;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

class ReauthenticationTest {

  private final MemberLogin login =
      new MemberLogin(7, UUID.randomUUID(), 11, UUID.randomUUID(), 0, false);
  private final MemberPrincipal member = MemberPrincipal.of(login, OAuthProvider.KAKAO);
  private final AccountStore accounts = mock(AccountStore.class);

  @AfterEach
  void clear() {
    SecurityContextHolder.clearContext();
    RequestContextHolder.resetRequestAttributes();
  }

  @Test
  @DisplayName(
      "회원이 reauth=true로 시작하면 카카오·구글은 prompt=login, 네이버는 auth_type=reauthenticate를 붙이고 세션에 기다리는 값을 둔다")
  void resolverAddsProviderPrompt() {
    signIn();
    ReauthAuthorizationRequestResolver resolver =
        new ReauthAuthorizationRequestResolver(
            new InMemoryClientRegistrationRepository(registration("kakao"), registration("naver")));

    MockHttpServletRequest kakao = start("kakao", true);
    OAuth2AuthorizationRequest kakaoRequest = resolver.resolve(kakao);
    assertThat(kakaoRequest.getAdditionalParameters()).containsEntry("prompt", "login");
    assertThat(kakaoRequest.getAuthorizationRequestUri()).contains("prompt=login");
    assertThat(Reauthentication.pending(kakao.getSession(false))).contains(7L);

    MockHttpServletRequest naver = start("naver", true);
    assertThat(resolver.resolve(naver).getAdditionalParameters())
        .containsEntry("auth_type", "reauthenticate");
  }

  @Test
  @DisplayName("로그인하지 않았거나 reauth가 없으면 일반 로그인이고 기다리는 값을 지운다")
  void resolverIgnoresWithoutMember() {
    ReauthAuthorizationRequestResolver resolver =
        new ReauthAuthorizationRequestResolver(
            new InMemoryClientRegistrationRepository(registration("kakao")));
    MockHttpServletRequest anonymous = start("kakao", true);
    assertThat(resolver.resolve(anonymous).getAdditionalParameters()).doesNotContainKey("prompt");
    assertThat(Reauthentication.pending(anonymous.getSession(false))).isEmpty();

    signIn();
    MockHttpServletRequest plain = start("kakao", false);
    Reauthentication.start(plain.getSession(), 7);
    assertThat(resolver.resolve(plain).getAdditionalParameters()).doesNotContainKey("prompt");
    assertThat(Reauthentication.pending(plain.getSession(false))).isEmpty();
  }

  @Test
  @DisplayName("재인증 중에는 가입시키지 않고, 세션 회원과 같은 계정이면 성공, 다르거나 모르는 계정이면 reauth_mismatch")
  void userServiceChecksSameAccount() {
    MockHttpSession session = pendingSession();
    when(accounts.find(OAuthProvider.KAKAO, "100")).thenReturn(Optional.of(login));
    when(accounts.find(OAuthProvider.KAKAO, "200"))
        .thenReturn(
            Optional.of(new MemberLogin(8, UUID.randomUUID(), 12, UUID.randomUUID(), 0, false)));
    when(accounts.find(OAuthProvider.KAKAO, "300")).thenReturn(Optional.empty());
    bindRequest(session);

    assertThat(((MemberPrincipal) userService("100").loadUser(userRequest())).memberId())
        .isEqualTo(7);
    for (String other : List.of("200", "300")) {
      assertThatThrownBy(() -> userService(other).loadUser(userRequest()))
          .isInstanceOf(OAuth2AuthenticationException.class)
          .satisfies(
              e ->
                  assertThat(((OAuth2AuthenticationException) e).getError().getErrorCode())
                      .isEqualTo(MemberOAuth2UserService.REAUTH_MISMATCH));
    }
    verify(accounts, never()).login(any(), any());
  }

  @Test
  @DisplayName("재인증이 성공하면 세션에 시각을 적고, 실패하면 기다리는 값만 지우고 세션은 남긴다")
  void handlersRecordOrClear() throws Exception {
    Instant now = Instant.parse("2026-11-08T15:00:00Z");
    MockHttpServletRequest ok = new MockHttpServletRequest();
    ok.setSession(pendingSession());
    new LoginSuccessHandler(null, null, null, java.time.Clock.fixed(now, java.time.ZoneOffset.UTC))
        .onAuthenticationSuccess(
            ok,
            new MockHttpServletResponse(),
            new OAuth2AuthenticationToken(member, member.getAuthorities(), "kakao"));
    assertThat(Reauthentication.lastAt(ok)).contains(now);
    assertThat(Reauthentication.pending(ok.getSession(false))).isEmpty();

    MockHttpServletRequest failed = new MockHttpServletRequest();
    MockHttpSession session = pendingSession();
    failed.setSession(session);
    MockHttpServletResponse response = new MockHttpServletResponse();
    new LoginFailureHandler()
        .onAuthenticationFailure(failed, response, new BadCredentialsException("x"));
    assertThat(response.getRedirectedUrl()).isEqualTo("/?reauth=failed");
    assertThat(Reauthentication.pending(session)).isEmpty();
    assertThat(session.isInvalid()).isFalse();
  }

  private MemberOAuth2UserService userService(String providerUserId) {
    return new MemberOAuth2UserService(
        r -> new DefaultOAuth2User(List.of(), Map.of("id", providerUserId), "id"),
        List.of(new KakaoUserInfoExtractor()),
        accounts);
  }

  private MockHttpSession pendingSession() {
    MockHttpSession session = new MockHttpSession();
    Reauthentication.start(session, 7);
    return session;
  }

  private static void bindRequest(MockHttpSession session) {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setSession(session);
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
  }

  private void signIn() {
    SecurityContextHolder.getContext()
        .setAuthentication(new OAuth2AuthenticationToken(member, member.getAuthorities(), "kakao"));
  }

  private static MockHttpServletRequest start(String registrationId, boolean reauth) {
    MockHttpServletRequest request =
        new MockHttpServletRequest("GET", "/oauth2/authorization/" + registrationId);
    request.setServletPath("/oauth2/authorization/" + registrationId);
    if (reauth) {
      request.setParameter(Reauthentication.PARAMETER, "true");
    }
    return request;
  }

  private static ClientRegistration registration(String id) {
    return ClientRegistration.withRegistrationId(id)
        .clientId("client")
        .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
        .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
        .authorizationUri("https://example.com/auth")
        .tokenUri("https://example.com/token")
        .userInfoUri("https://example.com/me")
        .userNameAttributeName("id")
        .build();
  }

  private static OAuth2UserRequest userRequest() {
    return new OAuth2UserRequest(
        registration("kakao"),
        new OAuth2AccessToken(
            OAuth2AccessToken.TokenType.BEARER,
            "t",
            Instant.parse("2026-11-08T00:00:00Z"),
            Instant.parse("2026-11-08T01:00:00Z")));
  }
}
