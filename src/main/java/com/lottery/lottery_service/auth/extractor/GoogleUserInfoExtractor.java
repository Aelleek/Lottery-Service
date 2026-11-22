package com.lottery.lottery_service.auth.extractor;

import com.lottery.lottery_service.auth.OAuth2UserInfoExtractor;
import com.lottery.lottery_service.auth.OAuthUserInfo;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Component;

/** Google 사용자 정보에서 OIDC 표준 속성 {@code sub}를 {@link OAuthUserInfo}로 꺼낸다. */
@Component
public class GoogleUserInfoExtractor implements OAuth2UserInfoExtractor {

  @Override
  public String supportsRegistrationId() {
    return "google";
  }

  /** {@code sub}가 제공자 식별자다. 없으면 {@code null}이다. */
  @Override
  public OAuthUserInfo extract(OAuth2User user) {
    return new OAuthUserInfo(Attributes.string(user.getAttributes(), "sub"));
  }
}
