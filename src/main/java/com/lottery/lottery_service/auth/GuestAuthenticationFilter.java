package com.lottery.lottery_service.auth;

import com.lottery.lottery_service.account.GuestCredentialStore;
import com.lottery.lottery_service.account.GuestIssueService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 회원 세션이 없는 요청에서 비회원 자격 쿠키를 확인해 {@link GuestPrincipal}을 요청 주체로 둔다. 세션에는 저장하지 않는다. 쿠키가 없거나 유효하지 않으면
 * 로그인하지 않은 요청으로 이어 간다.
 */
public class GuestAuthenticationFilter extends OncePerRequestFilter {

  private final GuestCredentialStore credentials;
  private final GuestIssueService issuer;

  /** 자격 저장소와 쿠키 설정으로 필터를 만든다. */
  public GuestAuthenticationFilter(GuestCredentialStore credentials, GuestIssueService issuer) {
    this.credentials = credentials;
    this.issuer = issuer;
  }

  /** 요청에 실린 비회원 자격 쿠키 원문. 없으면 {@code null}. */
  public static String cookieValue(HttpServletRequest request, String name) {
    Cookie[] cookies = request.getCookies();
    if (cookies == null) {
      return null;
    }
    for (Cookie cookie : cookies) {
      if (name.equals(cookie.getName())) {
        return cookie.getValue();
      }
    }
    return null;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    Authentication current = SecurityContextHolder.getContext().getAuthentication();
    if (current == null || !(current.getPrincipal() instanceof MemberPrincipal)) {
      String token = cookieValue(request, issuer.cookieName());
      if (token != null) {
        credentials
            .resolve(token)
            .ifPresent(
                subject -> {
                  PreAuthenticatedAuthenticationToken auth =
                      new PreAuthenticatedAuthenticationToken(
                          GuestPrincipal.of(subject),
                          null,
                          List.of(new SimpleGrantedAuthority("ROLE_GUEST")));
                  SecurityContextHolder.getContext().setAuthentication(auth);
                });
      }
    }
    chain.doFilter(request, response);
  }
}
