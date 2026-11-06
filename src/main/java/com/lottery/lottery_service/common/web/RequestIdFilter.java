package com.lottery.lottery_service.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** 요청마다 UUID 요청 ID를 만들어 요청 속성, 로그 MDC, {@code X-Request-Id} 응답 헤더에 둔다. 들어온 값은 쓰지 않는다. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

  /** 요청 ID를 담는 요청 속성 이름. */
  public static final String ATTRIBUTE = RequestIdFilter.class.getName() + ".requestId";

  /** 요청 ID 응답 헤더. */
  public static final String HEADER = "X-Request-Id";

  /** 로그 MDC 키. */
  public static final String MDC_KEY = "requestId";

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String requestId = UUID.randomUUID().toString();
    request.setAttribute(ATTRIBUTE, requestId);
    response.setHeader(HEADER, requestId);
    MDC.put(MDC_KEY, requestId);
    try {
      chain.doFilter(request, response);
    } finally {
      MDC.remove(MDC_KEY);
    }
  }

  /** 요청에 붙은 요청 ID. 필터를 거치지 않은 요청이면 새로 만들어 붙인다. */
  public static String requestIdOf(HttpServletRequest request) {
    Object value = request.getAttribute(ATTRIBUTE);
    if (value instanceof String id) {
      return id;
    }
    String id = UUID.randomUUID().toString();
    request.setAttribute(ATTRIBUTE, id);
    return id;
  }
}
