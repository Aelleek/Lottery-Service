package com.lottery.lottery_service.common.error;

/** API 오류 응답 본문. 형식은 openapi의 {@code Error}다. */
public record ErrorResponse(
    ErrorCode code, String message, boolean retryable, String requestId, ErrorDetails details) {

  /** details가 없으면 빈 객체로 채운다. */
  public ErrorResponse {
    if (details == null) {
      details = ErrorDetails.empty();
    }
  }
}
