package com.lottery.lottery_service.common.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 보안 응답 헤더 값(03 6.2절, C-29). 보안 필터 체인이 모든 응답에 붙이고, 체인보다 앞에서 끝나는 응답(요청 제한, 본문 상한, 방화벽 거절)은 이 클래스로 같은
 * 값을 붙인다.
 */
public final class SecurityHeaders {

  /** CSP. 인라인 스크립트·스타일을 쓰지 않는 것을 전제로 한다. */
  public static final String CONTENT_SECURITY_POLICY =
      "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:;"
          + " connect-src 'self'; object-src 'none'; base-uri 'self'; frame-ancestors 'none'";

  /** Permissions-Policy. */
  public static final String PERMISSIONS_POLICY = "camera=(self), microphone=(), geolocation=()";

  /** HSTS(HTTPS 요청에만). */
  public static final String STRICT_TRANSPORT_SECURITY = "max-age=31536000 ; includeSubDomains";

  private SecurityHeaders() {}

  /** 보안 필터 체인 앞에서 끝나는 응답에 체인과 같은 보안 헤더와 {@code Cache-Control: no-store}를 붙인다. */
  public static void apply(HttpServletRequest request, HttpServletResponse response) {
    response.setHeader("Content-Security-Policy", CONTENT_SECURITY_POLICY);
    response.setHeader("X-Frame-Options", "DENY");
    response.setHeader("X-Content-Type-Options", "nosniff");
    response.setHeader("Referrer-Policy", "strict-origin-when-cross-origin");
    response.setHeader("Permissions-Policy", PERMISSIONS_POLICY);
    response.setHeader("Cache-Control", "no-store");
    if (request.isSecure()) {
      response.setHeader("Strict-Transport-Security", STRICT_TRANSPORT_SECURITY);
    }
  }
}
