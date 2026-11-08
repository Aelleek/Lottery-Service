package com.lottery.lottery_service.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.lottery.lottery_service.account.AccountBlockedException;
import com.lottery.lottery_service.account.AccountStore;
import com.lottery.lottery_service.account.MemberLogin;
import com.lottery.lottery_service.auth.extractor.KakaoUserInfoExtractor;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;

class MemberOAuth2UserServiceTest {

  private final AccountStore accounts = mock(AccountStore.class);

  @Test
  @DisplayName("제공자 사용자 ID로만 로그인하고 세션에는 제공자 속성을 남기지 않는다")
  void loginByProviderIdOnly() {
    MemberLogin login = new MemberLogin(7, UUID.randomUUID(), 11, UUID.randomUUID(), 3, false);
    when(accounts.login(OAuthProvider.KAKAO, "12345")).thenReturn(login);

    OAuth2User user =
        service(Map.of("id", 12345L, "kakao_account", Map.of("email", "a@example.com")))
            .loadUser(request("kakao"));

    assertThat(user).isInstanceOf(MemberPrincipal.class);
    MemberPrincipal principal = (MemberPrincipal) user;
    assertThat(principal.memberId()).isEqualTo(7);
    assertThat(principal.subjectId()).isEqualTo(11);
    assertThat(principal.authVersion()).isEqualTo(3);
    assertThat(principal.getAttributes()).isEmpty();
    assertThat(principal.getAuthorities())
        .extracting(Object::toString)
        .containsExactly("ROLE_MEMBER");
    verify(accounts).login(OAuthProvider.KAKAO, "12345");
  }

  @Test
  @DisplayName("탈퇴 처리 중인 회원은 로그인 오류가 된다")
  void blockedAccountRejected() {
    when(accounts.login(OAuthProvider.KAKAO, "12345")).thenThrow(new AccountBlockedException(7));

    assertThatThrownBy(() -> service(Map.of("id", 12345L)).loadUser(request("kakao")))
        .isInstanceOf(OAuth2AuthenticationException.class)
        .satisfies(
            e ->
                assertThat(((OAuth2AuthenticationException) e).getError().getErrorCode())
                    .isEqualTo("account_blocked"));
  }

  @Test
  @DisplayName("지원하지 않는 제공자는 회원을 찾지 않고 거부한다")
  void unsupportedProviderRejected() {
    assertThatThrownBy(() -> service(Map.of("id", 1L)).loadUser(request("github")))
        .isInstanceOf(OAuth2AuthenticationException.class);
    verifyNoInteractions(accounts);
  }

  private MemberOAuth2UserService service(Map<String, Object> attributes) {
    OAuth2User delegateUser = new DefaultOAuth2User(List.of(), attributes, "id");
    return new MemberOAuth2UserService(
        r -> delegateUser, List.of(new KakaoUserInfoExtractor()), accounts);
  }

  private static OAuth2UserRequest request(String registrationId) {
    ClientRegistration registration =
        ClientRegistration.withRegistrationId(registrationId)
            .clientId("client")
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .redirectUri("http://localhost/login/oauth2/code/" + registrationId)
            .authorizationUri("https://example.com/auth")
            .tokenUri("https://example.com/token")
            .userInfoUri("https://example.com/me")
            .userNameAttributeName("id")
            .build();
    OAuth2AccessToken token =
        new OAuth2AccessToken(
            OAuth2AccessToken.TokenType.BEARER,
            "token",
            Instant.parse("2026-11-08T00:00:00Z"),
            Instant.parse("2026-11-08T01:00:00Z"));
    return new OAuth2UserRequest(registration, token);
  }
}
