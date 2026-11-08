package com.lottery.lottery_service.auth;

import com.lottery.lottery_service.common.error.BusinessException;
import com.lottery.lottery_service.common.error.ErrorCode;
import com.lottery.lottery_service.common.web.OriginGuard;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.util.Locale;
import java.util.Set;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * 쿠키로 인증한 변경 요청에 출처 검사와 {@code X-CSRF-TOKEN} 확인을 한다(03 6.1, D-051). 주체가 없는 요청은 여기서 막지 않고 권한 검사에
 * 맡긴다. 비회원 자격 발급은 쿠키 자격이 생기기 전 진입점이라 출처 검사만 하고, 즉시 확인은 주체를 쓰지 않아 검사하지 않는다.
 */
public class CsrfFilter extends OncePerRequestFilter {

  /** CSRF 토큰 헤더. */
  public static final String HEADER = "X-CSRF-TOKEN";

  private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");
  private static final Set<String> ORIGIN_ONLY = Set.of("/api/v1/guest-credentials");
  private static final Set<String> EXEMPT = Set.of("/api/v1/lotto/check");

  private final CsrfTokens tokens;
  private final OriginGuard originGuard;
  private final HandlerExceptionResolver errors;

  /** 토큰 확인기, 출처 검사기, 오류 응답기로 필터를 만든다. */
  public CsrfFilter(CsrfTokens tokens, OriginGuard originGuard, HandlerExceptionResolver errors) {
    this.tokens = tokens;
    this.originGuard = originGuard;
    this.errors = errors;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String path = request.getRequestURI().substring(request.getContextPath().length());
    if (SAFE_METHODS.contains(request.getMethod().toUpperCase(Locale.ROOT))
        || EXEMPT.contains(path)) {
      chain.doFilter(request, response);
      return;
    }
    try {
      originGuard.check(request);
      if (!ORIGIN_ONLY.contains(path)) {
        checkToken(request);
      }
    } catch (BusinessException e) {
      errors.resolveException(request, response, null, e);
      return;
    }
    chain.doFilter(request, response);
  }

  private void checkToken(HttpServletRequest request) {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    Object principal = auth == null ? null : auth.getPrincipal();
    String token = request.getHeader(HEADER);
    boolean ok;
    if (principal instanceof MemberPrincipal member) {
      HttpSession session = request.getSession(false);
      ok = session != null && tokens.verify(token, member, session.getId());
    } else if (principal instanceof GuestPrincipal guest) {
      ok = tokens.verify(token, guest);
    } else {
      return;
    }
    if (!ok) {
      throw new BusinessException(ErrorCode.CSRF_REJECTED, "요청을 확인할 수 없습니다. 화면을 새로 고쳐 주세요.");
    }
  }
}
