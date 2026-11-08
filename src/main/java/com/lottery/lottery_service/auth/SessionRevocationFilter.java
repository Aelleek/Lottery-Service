package com.lottery.lottery_service.auth;

import com.lottery.lottery_service.account.AccountStore;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 요청마다 회원 세션이 회수되지 않았는지 본다. 회원이 ACTIVE가 아니거나 {@code auth_version}이 바뀌었으면 세션을 지우고 로그인하지 않은 요청으로 이어
 * 간다(03 6.3).
 */
public class SessionRevocationFilter extends OncePerRequestFilter {

  private final AccountStore accounts;

  /** 계정 저장소로 필터를 만든다. */
  public SessionRevocationFilter(AccountStore accounts) {
    this.accounts = accounts;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth != null
        && auth.getPrincipal() instanceof MemberPrincipal principal
        && !accounts.sessionValid(principal.memberId(), principal.authVersion())) {
      SecurityContextHolder.clearContext();
      HttpSession session = request.getSession(false);
      if (session != null) {
        session.invalidate();
      }
    }
    chain.doFilter(request, response);
  }
}
