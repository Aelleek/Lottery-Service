package com.lottery.lottery_service.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;

/** 소셜 로그인이 실패하면 첫 화면으로 보낸다. 재인증이 실패하거나 취소되면 기다리던 재인증만 지우고 세션은 그대로 둔다(03 6.3). 오류 원문은 주소에 싣지 않는다. */
public class LoginFailureHandler implements AuthenticationFailureHandler {

  @Override
  public void onAuthenticationFailure(
      HttpServletRequest request, HttpServletResponse response, AuthenticationException exception)
      throws IOException {
    HttpSession session = request.getSession(false);
    boolean reauth = Reauthentication.pending(session).isPresent();
    Reauthentication.clear(session);
    response.sendRedirect(
        request.getContextPath() + (reauth ? "/?reauth=failed" : "/?login=failed"));
  }
}
