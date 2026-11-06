package com.lottery.lottery_service.common.error;

import java.util.Objects;

/** 오류 코드로 응답이 정해지는 업무 예외. {@link ApiExceptionHandler}가 오류 응답으로 바꾼다. */
public class BusinessException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final ErrorCode code;
  private final transient ErrorDetails details;
  private final boolean retryable;

  /** 기본 재시도 여부와 빈 details로 만든다. */
  public BusinessException(ErrorCode code, String message) {
    this(code, message, ErrorDetails.empty());
  }

  /** 기본 재시도 여부로 만든다. */
  public BusinessException(ErrorCode code, String message, ErrorDetails details) {
    this(code, message, details, Objects.requireNonNull(code, "code").retryable());
  }

  /** 모든 항목을 지정해 만든다. {@code message}는 사용자에게 보이므로 번호·연락처 같은 원문을 넣지 않는다. */
  public BusinessException(
      ErrorCode code, String message, ErrorDetails details, boolean retryable) {
    super(message);
    this.code = Objects.requireNonNull(code, "code");
    this.details = details == null ? ErrorDetails.empty() : details;
    this.retryable = retryable;
  }

  /** 오류 코드. */
  public ErrorCode getCode() {
    return code;
  }

  /** 응답 details. */
  public ErrorDetails getDetails() {
    return details;
  }

  /** 다시 시도해도 되는지. */
  public boolean isRetryable() {
    return retryable;
  }
}
