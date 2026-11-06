package com.lottery.lottery_service.common.web;

import com.lottery.lottery_service.common.error.BusinessException;
import com.lottery.lottery_service.common.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Locale;
import java.util.Set;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 쿠키로 인증하는 변경 요청의 출처를 확인한다(05 1절 2번, 03 6절). CSRF 토큰과 함께 쓰는 앞단 검사다.
 *
 * <ul>
 *   <li>{@code Sec-Fetch-Site}가 있으면 {@code same-origin}이나 {@code none}만 받는다. 다른 사이트와 같은 사이트의 다른 출처는
 *       막는다.
 *   <li>{@code Origin}이 있으면 허용 출처(설정이 없으면 서버 자신의 출처)와 같아야 한다. {@code null}은 막는다.
 *   <li>둘 다 없으면 브라우저가 아닌 요청으로 보고 통과시킨다. 이때도 CSRF 토큰 검사는 따로 한다.
 * </ul>
 */
@Component
@EnableConfigurationProperties(OriginProperties.class)
public class OriginGuard {

  private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");
  private static final Set<String> ALLOWED_FETCH_SITES = Set.of("same-origin", "none");

  private final OriginProperties properties;

  /** 검사기를 만든다. */
  public OriginGuard(OriginProperties properties) {
    this.properties = properties;
  }

  /**
   * 변경 요청의 출처를 확인한다. 읽기 요청은 그대로 통과한다.
   *
   * @throws BusinessException 출처가 맞지 않으면 {@code ORIGIN_REJECTED}
   */
  public void check(HttpServletRequest request) {
    if (SAFE_METHODS.contains(request.getMethod().toUpperCase(Locale.ROOT))) {
      return;
    }
    String fetchSite = request.getHeader("Sec-Fetch-Site");
    if (fetchSite != null && !ALLOWED_FETCH_SITES.contains(fetchSite.toLowerCase(Locale.ROOT))) {
      throw rejected();
    }
    String origin = request.getHeader("Origin");
    if (origin == null) {
      return;
    }
    String normalized = normalize(origin);
    boolean allowed =
        properties.allowedOrigins().isEmpty()
            ? normalized.equals(selfOrigin(request))
            : properties.allowedOrigins().stream()
                .map(OriginGuard::normalize)
                .anyMatch(normalized::equals);
    if (!allowed) {
      throw rejected();
    }
  }

  private static String selfOrigin(HttpServletRequest request) {
    String scheme = request.getScheme().toLowerCase(Locale.ROOT);
    int port = request.getServerPort();
    boolean defaultPort =
        ("http".equals(scheme) && port == 80) || ("https".equals(scheme) && port == 443);
    return scheme
        + "://"
        + request.getServerName().toLowerCase(Locale.ROOT)
        + (defaultPort || port <= 0 ? "" : ":" + port);
  }

  private static String normalize(String origin) {
    String value = origin.trim().toLowerCase(Locale.ROOT);
    if (value.endsWith("/")) {
      value = value.substring(0, value.length() - 1);
    }
    if (value.startsWith("https://") && value.endsWith(":443")) {
      return value.substring(0, value.length() - 4);
    }
    if (value.startsWith("http://") && value.endsWith(":80")) {
      return value.substring(0, value.length() - 3);
    }
    return value;
  }

  private static BusinessException rejected() {
    return new BusinessException(ErrorCode.ORIGIN_REJECTED, "허용되지 않은 요청입니다.");
  }
}
