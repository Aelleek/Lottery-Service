package com.lottery.lottery_service.common.web;

import com.lottery.lottery_service.common.error.BusinessException;
import com.lottery.lottery_service.common.error.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * 64KB를 넘는 요청 본문은 받지 않는다(03 6.4). {@code Content-Length}로 먼저 거르고, 길이를 알리지 않은 본문은 읽는 도중 넘으면 실패시킨다.
 */
public class BodySizeFilter extends OncePerRequestFilter {

  /** 받는 본문의 최대 바이트. */
  public static final int MAX_BYTES = 64 * 1024;

  private final HandlerExceptionResolver errors;

  /** 오류 응답기로 필터를 만든다. */
  public BodySizeFilter(HandlerExceptionResolver errors) {
    this.errors = errors;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    if (request.getContentLengthLong() > MAX_BYTES) {
      errors.resolveException(
          request,
          response,
          null,
          new BusinessException(ErrorCode.VALIDATION_FAILED, "요청 본문이 너무 큽니다."));
      return;
    }
    chain.doFilter(new LimitedBodyRequest(request, MAX_BYTES), response);
  }
}
