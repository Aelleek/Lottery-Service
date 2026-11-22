package com.lottery.lottery_service.auth.extractor;

import com.lottery.lottery_service.auth.OAuth2UserInfoExtractor;
import com.lottery.lottery_service.auth.OAuthUserInfo;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Component;

/**
 * Kakao 사용자 정보에서 최상위 {@code id}를 {@link OAuthUserInfo}로 꺼낸다. 예: {@code {"id": 123, "kakao_account":
 * {...}}}
 */
@Component
public class KakaoUserInfoExtractor implements OAuth2UserInfoExtractor {

  @Override
  public String supportsRegistrationId() {
    return "kakao";
  }

  /** {@code id}가 제공자 식별자다. 없으면 {@code null}이다. */
  @Override
  public OAuthUserInfo extract(OAuth2User user) {
    return new OAuthUserInfo(Attributes.string(user.getAttributes(), "id"));
  }
}
