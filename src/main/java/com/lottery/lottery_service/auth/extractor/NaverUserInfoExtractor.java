package com.lottery.lottery_service.auth.extractor;

import com.lottery.lottery_service.auth.OAuth2UserInfoExtractor;
import com.lottery.lottery_service.auth.OAuthUserInfo;
import java.util.Map;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Component;

/**
 * Naver 사용자 정보를 {@link OAuthUserInfo}로 바꾼다. Naver는 값을 {@code response} 아래에 담는다. 예: {@code
 * {"resultcode": "00", "response": {"id": "...", "email": "...", "nickname": "..."}}}
 */
@Component
public class NaverUserInfoExtractor implements OAuth2UserInfoExtractor {

  @Override
  public String supportsRegistrationId() {
    return "naver";
  }

  /** {@code response.id}가 제공자 식별자다. {@code response}가 없거나 값이 없으면 {@code null}로 둔다. */
  @Override
  public OAuthUserInfo extract(OAuth2User user) {
    Map<?, ?> response =
        user.getAttributes().get("response") instanceof Map<?, ?> map ? map : Map.of();
    return new OAuthUserInfo(
        Attributes.string(response, "id"),
        Attributes.string(response, "email"),
        Attributes.string(response, "name"),
        Attributes.string(response, "nickname"),
        Attributes.string(response, "profile_image"));
  }
}
