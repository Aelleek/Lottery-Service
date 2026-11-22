package com.lottery.lottery_service.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OAuthProviderTest {

  @Test
  @DisplayName("등록 키는 대소문자를 무시하고 제공자로 바꾼다")
  void mapsRegistrationIdIgnoringCase() {
    assertThat(OAuthProvider.fromRegistrationId("kakao")).contains(OAuthProvider.KAKAO);
    assertThat(OAuthProvider.fromRegistrationId("NAVER")).contains(OAuthProvider.NAVER);
    assertThat(OAuthProvider.fromRegistrationId("Google")).contains(OAuthProvider.GOOGLE);
  }

  @Test
  @DisplayName("null이나 모르는 등록 키는 빈 값이다")
  void rejectsNullAndUnknownIds() {
    assertThat(OAuthProvider.fromRegistrationId(null)).isEmpty();
    assertThat(OAuthProvider.fromRegistrationId("github")).isEmpty();
    assertThat(OAuthProvider.fromRegistrationId("")).isEmpty();
  }
}
