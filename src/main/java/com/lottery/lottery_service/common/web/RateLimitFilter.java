package com.lottery.lottery_service.common.web;

import com.lottery.lottery_service.common.error.BusinessException;
import com.lottery.lottery_service.common.error.ErrorCode;
import com.lottery.lottery_service.common.error.ErrorDetails;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * IP별 요청 제한(D-048, 03 6.5). 넘으면 429 {@code RATE_LIMITED}와 {@code Retry-After}다. IP는 연결 주소에서만 읽고 요청이
 * 보낸 {@code X-Forwarded-For}는 믿지 않는다. 앞단 뒤에 둘 때는 앞단이 붙인 헤더를 쓰도록 서버 설정을 바꾼다.
 *
 * <ul>
 *   <li>현재 회차·회차 결과 조회: 1분 120회
 *   <li>즉시 확인: 1분 30회
 *   <li>로그인 시작: 1분 20회
 *   <li>모든 API와 로그인 시작 합계: 1분 300회
 * </ul>
 */
public class RateLimitFilter extends OncePerRequestFilter {

  private static final Duration MINUTE = Duration.ofMinutes(1);
  private static final Pattern ROUND_RESULT =
      Pattern.compile("^/api/v1/lotto/rounds/[^/]+/result$");

  private final IpRateLimiter lookups;
  private final IpRateLimiter checks;
  private final IpRateLimiter logins;
  private final IpRateLimiter total;
  private final HandlerExceptionResolver errors;

  /** 설계 한도로 필터를 만든다. */
  public RateLimitFilter(Clock clock, HandlerExceptionResolver errors) {
    this.lookups = new IpRateLimiter(120, MINUTE, clock);
    this.checks = new IpRateLimiter(30, MINUTE, clock);
    this.logins = new IpRateLimiter(20, MINUTE, clock);
    this.total = new IpRateLimiter(300, MINUTE, clock);
    this.errors = errors;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String path = request.getRequestURI().substring(request.getContextPath().length());
    boolean login = path.startsWith("/oauth2/authorization/");
    if (!login && !path.startsWith("/api/")) {
      chain.doFilter(request, response);
      return;
    }
    String ip = request.getRemoteAddr();
    Optional<Instant> retryAt = total.tryAcquire(ip);
    if (retryAt.isEmpty()) {
      IpRateLimiter specific = specific(path, request.getMethod(), login);
      if (specific != null) {
        retryAt = specific.tryAcquire(ip);
      }
    }
    if (retryAt.isPresent()) {
      errors.resolveException(
          request,
          response,
          null,
          new BusinessException(
              ErrorCode.RATE_LIMITED, "잠시 뒤 다시 시도해 주세요.", ErrorDetails.retryAt(retryAt.get())));
      return;
    }
    chain.doFilter(request, response);
  }

  private IpRateLimiter specific(String path, String method, boolean login) {
    if (login) {
      return logins;
    }
    if ("POST".equalsIgnoreCase(method) && "/api/v1/lotto/check".equals(path)) {
      return checks;
    }
    if ("GET".equalsIgnoreCase(method)
        && ("/api/v1/lotto/context".equals(path) || ROUND_RESULT.matcher(path).matches())) {
      return lookups;
    }
    return null;
  }
}
