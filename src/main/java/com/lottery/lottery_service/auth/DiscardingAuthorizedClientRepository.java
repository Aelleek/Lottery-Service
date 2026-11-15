package com.lottery.lottery_service.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;

/**
 * 소셜 로그인 뒤 제공자 토큰을 남기지 않는다. 로그인에는 제공자 사용자 ID만 쓰고 그 뒤 제공자 API를 부르지 않으므로, 기본 저장소(메모리)에 회원마다 토큰이 쌓여 탈퇴
 * 뒤에도 남는 것을 막는다.
 */
public class DiscardingAuthorizedClientRepository implements OAuth2AuthorizedClientRepository {

  @Override
  public <T extends OAuth2AuthorizedClient> T loadAuthorizedClient(
      String clientRegistrationId, Authentication principal, HttpServletRequest request) {
    return null;
  }

  @Override
  public void saveAuthorizedClient(
      OAuth2AuthorizedClient authorizedClient,
      Authentication principal,
      HttpServletRequest request,
      HttpServletResponse response) {}

  @Override
  public void removeAuthorizedClient(
      String clientRegistrationId,
      Authentication principal,
      HttpServletRequest request,
      HttpServletResponse response) {}
}
