package com.lottery.lottery_service.auth;

import org.springframework.security.oauth2.core.user.OAuth2User;

/**
 * OAuth2User → OAuthUserInfo 변환기 인터페이스. 각 제공자별 구현체는 supportsRegistrationId()에 자신이 처리할
 * registrationId를 반환한다.
 */
public interface OAuth2UserInfoExtractor {

  /** 이 추출기가 처리하는 registrationId를 반환. 예: "kakao", "naver", "google" */
  String supportsRegistrationId();

  /**
   * provider 고유 attributes에서 공통 DTO(OAuthUserInfo)로 변환한다.
   *
   * @param user Spring Security가 제공하는 OAuth2User (attributes 포함)
   * @return 표준화된 사용자 정보
   */
  OAuthUserInfo extract(OAuth2User user);

  /** 주어진 registrationId를 이 추출기가 처리 가능한지 여부. 기본 구현은 문자열 일치(대소문자 무시)로 판단한다. */
  default boolean supports(String registrationId) {
    return registrationId != null && registrationId.equalsIgnoreCase(supportsRegistrationId());
  }
}
