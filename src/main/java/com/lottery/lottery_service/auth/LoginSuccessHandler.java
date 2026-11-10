package com.lottery.lottery_service.auth;

import com.lottery.lottery_service.account.ClaimResult;
import com.lottery.lottery_service.account.GuestCredentialStore;
import com.lottery.lottery_service.account.GuestIssueService;
import com.lottery.lottery_service.account.SubjectClaimStore;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;

/**
 * 로그인에 성공하면 요청에 실린 비회원 자격의 주체를 그 회원에게 귀속한다(05 4절 1·2번). 귀속이 실패해도 로그인은 성공한다. 귀속을 시도한 뒤에는 비회원 쿠키를
 * 지운다. 기록 가져오기는 회원이 따로 확인할 때 시작한다.
 */
public class LoginSuccessHandler extends SavedRequestAwareAuthenticationSuccessHandler {

  private static final Logger log = LoggerFactory.getLogger(LoginSuccessHandler.class);

  private final GuestCredentialStore credentials;
  private final GuestIssueService issuer;
  private final SubjectClaimStore claims;
  private final Clock clock;

  /** 처리기를 만든다. 다른 곳에서 오지 않은 로그인은 첫 화면으로 보낸다. */
  public LoginSuccessHandler(
      GuestCredentialStore credentials,
      GuestIssueService issuer,
      SubjectClaimStore claims,
      Clock clock) {
    this.credentials = credentials;
    this.issuer = issuer;
    this.claims = claims;
    this.clock = clock;
    setDefaultTargetUrl("/");
  }

  @Override
  public void onAuthenticationSuccess(
      HttpServletRequest request, HttpServletResponse response, Authentication authentication)
      throws ServletException, IOException {
    HttpSession session = request.getSession(false);
    if (Reauthentication.pending(session).isPresent()) {
      Reauthentication.complete(session, clock.instant());
    } else if (authentication.getPrincipal() instanceof MemberPrincipal member) {
      SessionLifetime.markStarted(request.getSession(), clock.instant());
      String token = GuestAuthenticationFilter.cookieValue(request, issuer.cookieName());
      if (token != null) {
        claimQuietly(token, member);
        response.addHeader(HttpHeaders.SET_COOKIE, expiredGuestCookie().toString());
      }
    }
    super.onAuthenticationSuccess(request, response, authentication);
  }

  private void claimQuietly(String token, MemberPrincipal member) {
    try {
      credentials
          .resolve(token)
          .ifPresent(
              guest -> {
                ClaimResult result = claims.claim(guest.subjectId(), member.subjectId());
                log.info("guest claim result={}", result);
              });
    } catch (RuntimeException e) {
      log.warn("guest claim failed", e);
    }
  }

  private ResponseCookie expiredGuestCookie() {
    return issuer.expiredCookie();
  }
}
