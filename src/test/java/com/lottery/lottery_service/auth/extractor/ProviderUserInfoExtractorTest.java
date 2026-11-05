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

  @Test
  @DisplayName("Google은 sub를 식별자로 쓰고 이름·이메일·사진을 읽는다")
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

    assertThat(info)
        .isEqualTo(
            new OAuthUserInfo(
                "109876543210", "a@example.com", "홍길동", "홍길동", "https://example.com/p.png"));
  }

  @Test
  @DisplayName("Google에서 동의하지 않은 값은 null이다")
  void googleMissingOptionalValues() {
    OAuthUserInfo info = google.extract(user(Map.of("sub", "1", "email", ""), "sub"));

    assertThat(info).isEqualTo(new OAuthUserInfo("1", null, null, null, null));
  }

  @Test
  @DisplayName("Naver는 response 아래 id를 식별자로 쓴다")
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

    assertThat(info)
        .isEqualTo(
            new OAuthUserInfo(
                "naver-id-1", "b@example.com", "김철수", "철수", "https://example.com/n.png"));
  }

  @Test
  @DisplayName("Naver response에 id가 없으면 식별자는 null이라 로그인 처리에서 거부된다")
  void naverWithoutIdGivesNullId() {
    OAuthUserInfo info =
        naver.extract(user(Map.of("response", Map.of("nickname", "철수")), "response"));

    assertThat(info.providerUserId()).isNull();
    assertThat(info.nickname()).isEqualTo("철수");
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

  private static OAuth2User user(Map<String, Object> attrs, String nameKey) {
    return new DefaultOAuth2User(List.of(new SimpleGrantedAuthority("ROLE_USER")), attrs, nameKey);
  }
}
