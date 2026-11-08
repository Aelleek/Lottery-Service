package com.lottery.lottery_service.auth;

import jakarta.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

/**
 * 로그인 시작 요청에 {@code reauth=true}가 있고 회원 세션이면 제공자에게 로그인 화면을 다시 띄우라고 요청한다(C-31). 구글·카카오는 {@code
 * prompt=login}, 네이버는 {@code auth_type=reauthenticate}다 [미확인]. 로그인하지 않은 요청의 {@code reauth}는 무시하고 일반
 * 로그인으로 처리한다.
 */
public class ReauthAuthorizationRequestResolver implements OAuth2AuthorizationRequestResolver {

  private static final String BASE_URI = "/oauth2/authorization";

  private final DefaultOAuth2AuthorizationRequestResolver delegate;

  /** 클라이언트 등록 정보로 만든다. */
  public ReauthAuthorizationRequestResolver(ClientRegistrationRepository registrations) {
    this.delegate = new DefaultOAuth2AuthorizationRequestResolver(registrations, BASE_URI);
  }

  @Override
  public OAuth2AuthorizationRequest resolve(HttpServletRequest request) {
    return customize(request, delegate.resolve(request));
  }

  @Override
  public OAuth2AuthorizationRequest resolve(HttpServletRequest request, String registrationId) {
    return customize(request, delegate.resolve(request, registrationId));
  }

  private OAuth2AuthorizationRequest customize(
      HttpServletRequest request, OAuth2AuthorizationRequest original) {
    if (original == null) {
      return null;
    }
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    boolean wanted = "true".equals(request.getParameter(Reauthentication.PARAMETER));
    if (!wanted || auth == null || !(auth.getPrincipal() instanceof MemberPrincipal member)) {
      Reauthentication.clear(request.getSession(false));
      return original;
    }
    Reauthentication.start(request.getSession(), member.memberId());
    String registrationId = (String) original.getAttributes().get("registration_id");
    Map<String, Object> extra = new HashMap<>(original.getAdditionalParameters());
    if ("naver".equals(registrationId)) {
      extra.put("auth_type", "reauthenticate");
    } else {
      extra.put("prompt", "login");
    }
    return OAuth2AuthorizationRequest.from(original).additionalParameters(extra).build();
  }
}
