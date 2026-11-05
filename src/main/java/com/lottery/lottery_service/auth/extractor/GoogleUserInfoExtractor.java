package com.lottery.lottery_service.auth.extractor;

import com.lottery.lottery_service.auth.OAuth2UserInfoExtractor;
import com.lottery.lottery_service.auth.OAuthUserInfo;
import java.util.Map;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Component;

/** Google 사용자 정보(OIDC 표준 속성 {@code sub, email, name, picture})를 {@link OAuthUserInfo}로 바꾼다. */
@Component
public class GoogleUserInfoExtractor implements OAuth2UserInfoExtractor {

  @Override
  public String supportsRegistrationId() {
    return "google";
  }

  /** {@code sub}가 제공자 식별자다. 없는 값은 {@code null}로 둔다. */
  @Override
  public OAuthUserInfo extract(OAuth2User user) {
    Map<String, Object> attrs = user.getAttributes();
    String name = Attributes.string(attrs, "name");
    return new OAuthUserInfo(
        Attributes.string(attrs, "sub"),
        Attributes.string(attrs, "email"),
        name,
        name,
        Attributes.string(attrs, "picture"));
  }
}
