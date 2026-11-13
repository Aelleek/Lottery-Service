package com.lottery.lottery_service.auth;

import com.lottery.lottery_service.account.AccountBlockedException;
import com.lottery.lottery_service.account.AccountStore;
import jakarta.servlet.http.HttpSession;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** 소셜 로그인 사용자 정보를 회원으로 잇는다. (제공자, 제공자 사용자 ID)로만 회원을 찾고 이메일은 읽지 않는다(D-007, D-038). */
@Service
public class MemberOAuth2UserService implements OAuth2UserService<OAuth2UserRequest, OAuth2User> {

  /** 재인증에서 세션 회원과 다른 소셜 계정이 돌아왔을 때의 오류 코드. */
  public static final String REAUTH_MISMATCH = "reauth_mismatch";

  private final OAuth2UserService<OAuth2UserRequest, OAuth2User> delegate;
  private final List<OAuth2UserInfoExtractor> extractors;
  private final AccountStore accounts;

  /** 시간 제한을 둔 사용자 정보 조회기로 서비스를 만든다(03 6.4). */
  @Autowired
  public MemberOAuth2UserService(List<OAuth2UserInfoExtractor> extractors, AccountStore accounts) {
    this(OAuthClientHttp.userService(OAuthClientHttp.TIMEOUT), extractors, accounts);
  }

  MemberOAuth2UserService(
      OAuth2UserService<OAuth2UserRequest, OAuth2User> delegate,
      List<OAuth2UserInfoExtractor> extractors,
      AccountStore accounts) {
    this.delegate = delegate;
    this.extractors = extractors;
    this.accounts = accounts;
  }

  /** 제공자 사용자 정보를 읽어 회원 로그인으로 바꾼다. 탈퇴 처리 중인 회원이면 로그인을 거부한다. */
  @Override
  public OAuth2User loadUser(OAuth2UserRequest request) {
    String registrationId = request.getClientRegistration().getRegistrationId();
    OAuthProvider provider =
        OAuthProvider.fromRegistrationId(registrationId)
            .orElseThrow(() -> failure("unsupported_provider"));
    OAuth2UserInfoExtractor extractor =
        extractors.stream()
            .filter(e -> e.supports(registrationId))
            .findFirst()
            .orElseThrow(() -> failure("unsupported_provider"));
    String providerUserId = extractor.extract(delegate.loadUser(request)).providerUserId();
    if (providerUserId == null || providerUserId.isBlank()) {
      throw failure("missing_provider_user_id");
    }
    Optional<Long> reauthFor = Reauthentication.pending(currentSession());
    try {
      if (reauthFor.isPresent()) {
        return accounts
            .find(provider, providerUserId)
            .filter(found -> found.memberId() == reauthFor.get())
            .map(found -> MemberPrincipal.of(found, provider))
            .orElseThrow(() -> failure(REAUTH_MISMATCH));
      }
      return MemberPrincipal.of(accounts.login(provider, providerUserId), provider);
    } catch (AccountBlockedException e) {
      throw failure("account_blocked");
    } catch (IllegalArgumentException e) {
      throw failure("invalid_provider_user_id");
    }
  }

  private static HttpSession currentSession() {
    if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
      return attrs.getRequest().getSession(false);
    }
    return null;
  }

  private static OAuth2AuthenticationException failure(String code) {
    return new OAuth2AuthenticationException(new OAuth2Error(code));
  }
}
