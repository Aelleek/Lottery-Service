package com.lottery.lottery_service.auth.extractor;

import static org.assertj.core.api.Assertions.assertThat;

import com.lottery.lottery_service.auth.OAuthUserInfo;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;

class ProviderUserInfoExtractorTest {

  private final GoogleUserInfoExtractor google = new GoogleUserInfoExtractor();
  private final NaverUserInfoExtractor naver = new NaverUserInfoExtractor();
  private final KakaoUserInfoExtractor kakao = new KakaoUserInfoExtractor();

  @Test
  @DisplayName("Google은 sub만 식별자로 꺼내고 이메일·이름·사진은 담지 않는다")
  void google() {
    OAuthUserInfo info =
        google.extract(
            user(
                Map.of(
                    "sub", "109876543210",
                    "email", "a@example.com",
                    "name", "홍길동",
                    "picture", "https://example.com/p.png"),
                "sub"));

    assertThat(info).isEqualTo(new OAuthUserInfo("109876543210"));
  }

  @Test
  @DisplayName("Google 응답에 sub가 비어 있으면 식별자는 null이다")
  void googleBlankSubGivesNullId() {
    OAuthUserInfo info = google.extract(user(Map.of("sub", "", "email", "a@example.com"), "email"));

    assertThat(info.providerUserId()).isNull();
  }

  @Test
  @DisplayName("Naver는 response 아래 id만 식별자로 꺼내고 이메일·이름·사진은 담지 않는다")
  void naver() {
    Map<String, Object> response =
        Map.of(
            "id", "naver-id-1",
            "email", "b@example.com",
            "name", "김철수",
            "nickname", "철수",
            "profile_image", "https://example.com/n.png");
    OAuthUserInfo info =
        naver.extract(user(Map.of("resultcode", "00", "response", response), "response"));

    assertThat(info).isEqualTo(new OAuthUserInfo("naver-id-1"));
  }

  @Test
  @DisplayName("Naver response에 id가 없으면 식별자는 null이라 로그인 처리에서 거부된다")
  void naverWithoutIdGivesNullId() {
    OAuthUserInfo info =
        naver.extract(user(Map.of("response", Map.of("nickname", "철수")), "response"));

    assertThat(info.providerUserId()).isNull();
  }

  @Test
  @DisplayName("registrationId는 대소문자를 무시하고 고른다")
  void supportsRegistrationId() {
    assertThat(google.supports("GOOGLE")).isTrue();
    assertThat(naver.supports("naver")).isTrue();
    assertThat(naver.supports("kakao")).isFalse();
    assertThat(google.supports(null)).isFalse();
  }

  @Test
  @DisplayName("숫자 식별자도 문자열로 읽는다")
  void numericValuesBecomeStrings() {
    assertThat(Attributes.string(Map.of("id", 42L), "id")).isEqualTo("42");
    assertThat(Attributes.string(Map.of("id", List.of()), "id")).isNull();
    assertThat(Attributes.string(Map.of(), "id")).isNull();
  }

  @Test
  @DisplayName("Kakao는 숫자 id를 식별자로 읽고 계정·프로필 값은 담지 않는다")
  void kakao() {
    Map<String, Object> account =
        Map.of(
            "email",
            "a@example.com",
            "profile",
            Map.of("nickname", "영희", "profile_image_url", "https://img/1"));
    OAuthUserInfo info =
        kakao.extract(user(Map.of("id", 4433749540L, "kakao_account", account), "id"));

    assertThat(info).isEqualTo(new OAuthUserInfo("4433749540"));
  }

  @Test
  @DisplayName("Kakao 응답에 id가 없으면 문자열 \"null\"이 아니라 null이다")
  void kakaoWithoutIdGivesNullId() {
    OAuthUserInfo info =
        kakao.extract(user(Map.of("kakao_account", Map.of("email", "")), "kakao_account"));

    assertThat(info.providerUserId()).isNull();
  }

  private static OAuth2User user(Map<String, Object> attrs, String nameKey) {
    return new DefaultOAuth2User(List.of(new SimpleGrantedAuthority("ROLE_USER")), attrs, nameKey);
  }
}
