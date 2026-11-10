package com.lottery.lottery_service.auth.extractor;

import com.lottery.lottery_service.auth.OAuth2UserInfoExtractor;
import com.lottery.lottery_service.auth.OAuthUserInfo;
import java.util.Map;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Component;

/**
 * Kakao 사용자 정보를 {@link OAuthUserInfo}로 바꾼다. 응답 구조는 {@code {"id": 123, "kakao_account": {"email":
 * ..., "profile": {"nickname": ..., "profile_image_url": ...}}}}이다.
 */
@Component
public class KakaoUserInfoExtractor implements OAuth2UserInfoExtractor {

  @Override
  public String supportsRegistrationId() {
    return "kakao";
  }

  /** {@code id}가 제공자 식별자다. 없는 값은 {@code null}로 둔다. */
  @Override
  public OAuthUserInfo extract(OAuth2User user) {
    Map<String, Object> attrs = user.getAttributes();
    String email = null;
    String name = null;
    String nickname = null;
    String profileImage = null;
    if (attrs.get("kakao_account") instanceof Map<?, ?> account) {
      email = Attributes.string(account, "email");
      if (account.get("profile") instanceof Map<?, ?> profile) {
        nickname = Attributes.string(profile, "nickname");
        name = Attributes.string(profile, "name");
        profileImage = Attributes.string(profile, "profile_image_url");
      }
    }
    return new OAuthUserInfo(Attributes.string(attrs, "id"), email, name, nickname, profileImage);
  }
}
