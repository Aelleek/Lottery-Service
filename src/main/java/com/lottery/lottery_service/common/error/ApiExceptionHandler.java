package com.lottery.lottery_service.common.error;

import com.lottery.lottery_service.common.number.InvalidLottoNumberException;
import com.lottery.lottery_service.common.web.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 업무 예외와 입력 오류를 openapi {@code Error} 형식으로 바꾼다. 429와 503에 재시도 시각이 있으면 {@code Retry-After}를 붙인다.
 *
 * <p>그 밖의 예외는 상태가 정해진 4xx면 그 상태로, 나머지는 500 {@code INTERNAL_ERROR}로 답한다. 인증·인가 예외는 Spring Security가
 * 처리하도록 다시 던진다.
 */
@Slf4j
@RestControllerAdvice
@RequiredArgsConstructor
public class ApiExceptionHandler {

  static final String VALIDATION_MESSAGE = "입력값을 확인해 주세요.";
  static final String NUMBERS_MESSAGE = "번호는 서로 다른 1~45 정수 6개여야 합니다.";
  static final String INTERNAL_MESSAGE = "요청을 처리하지 못했습니다. 문제가 계속되면 문의해 주세요.";
  static final String NOT_FOUND_MESSAGE = "요청한 내용을 찾을 수 없습니다.";
  static final String UNAUTHENTICATED_MESSAGE = "다시 로그인해 주세요.";
  static final String FORBIDDEN_MESSAGE = "허용되지 않은 요청입니다.";

  private final Clock clock;

  /** 업무 예외를 그 코드의 상태와 본문으로 응답한다. */
  @ExceptionHandler(BusinessException.class)
  public ResponseEntity<ErrorResponse> handleBusiness(
      BusinessException e, HttpServletRequest request) {
    log.debug("business error code={}", e.getCode());
    return respond(
        e.getCode(),
        e.getCode().status(),
        e.getMessage(),
        e.isRetryable(),
        e.getDetails(),
        request);
  }

  /** 요청 본문 검증 실패. 위치와 제약 이름만 돌려준다. */
  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<ErrorResponse> handleInvalidBody(
      MethodArgumentNotValidException e, HttpServletRequest request) {
    List<FieldErrorDetail> fieldErrors = new ArrayList<>();
    for (FieldError error : e.getBindingResult().getFieldErrors()) {
      fieldErrors.add(new FieldErrorDetail(error.getField(), String.valueOf(error.getCode())));
    }
    e.getBindingResult()
        .getGlobalErrors()
        .forEach(
            error ->
                fieldErrors.add(
                    new FieldErrorDetail(error.getObjectName(), String.valueOf(error.getCode()))));
    return respond(
        ErrorCode.VALIDATION_FAILED,
        HttpStatus.BAD_REQUEST,
        VALIDATION_MESSAGE,
        false,
        ErrorDetails.fieldErrors(fieldErrors),
        request);
  }

  /** 읽을 수 없는 본문(깨진 JSON, 맞지 않는 타입). */
  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<ErrorResponse> handleUnreadable(
      HttpMessageNotReadableException e, HttpServletRequest request) {
    return respond(
        ErrorCode.VALIDATION_FAILED,
        HttpStatus.BAD_REQUEST,
        VALIDATION_MESSAGE,
        false,
        ErrorDetails.empty(),
        request);
  }

  /** 번호 규칙 위반(범위, 개수, 중복). 입력한 번호는 응답에 다시 싣지 않는다. */
  @ExceptionHandler(InvalidLottoNumberException.class)
  public ResponseEntity<ErrorResponse> handleInvalidNumbers(
      InvalidLottoNumberException e, HttpServletRequest request) {
    return respond(
        ErrorCode.VALIDATION_FAILED,
        HttpStatus.UNPROCESSABLE_ENTITY,
        NUMBERS_MESSAGE,
        false,
        ErrorDetails.empty(),
        request);
  }

  /**
   * 위에서 다루지 않은 예외. 없는 경로, 허용되지 않은 메서드처럼 Spring이 4xx로 정한 것은 그 상태로 답하고, 나머지는 원인을 기록한 뒤 500으로 답한다. 예외
   * 메시지는 응답에 싣지 않는다.
   */
  @ExceptionHandler(Exception.class)
  public ResponseEntity<ErrorResponse> handleUnexpected(Exception e, HttpServletRequest request)
      throws Exception {
    if (e instanceof AccessDeniedException || e instanceof AuthenticationException) {
      throw e;
    }
    HttpStatusCode declared = declaredStatus(e);
    if (declared != null && declared.is4xxClientError()) {
      ErrorCode code = clientErrorCode(declared);
      return respond(code, declared, clientMessage(code), false, ErrorDetails.empty(), request);
    }
    log.error("unhandled error requestId={}", RequestIdFilter.requestIdOf(request), e);
    return respond(
        ErrorCode.INTERNAL_ERROR,
        ErrorCode.INTERNAL_ERROR.status(),
        INTERNAL_MESSAGE,
        false,
        ErrorDetails.empty(),
        request);
  }

  private static HttpStatusCode declaredStatus(Exception e) {
    if (e instanceof org.springframework.web.ErrorResponse standard) {
      return standard.getStatusCode();
    }
    ResponseStatus annotated =
        AnnotatedElementUtils.findMergedAnnotation(e.getClass(), ResponseStatus.class);
    return annotated == null ? null : annotated.code();
  }

  private static ErrorCode clientErrorCode(HttpStatusCode status) {
    return switch (status.value()) {
      case 401 -> ErrorCode.UNAUTHENTICATED;
      case 403 -> ErrorCode.FORBIDDEN;
      case 404 -> ErrorCode.NOT_FOUND;
      default -> ErrorCode.VALIDATION_FAILED;
    };
  }

  private static String clientMessage(ErrorCode code) {
    return switch (code) {
      case UNAUTHENTICATED -> UNAUTHENTICATED_MESSAGE;
      case FORBIDDEN -> FORBIDDEN_MESSAGE;
      case NOT_FOUND -> NOT_FOUND_MESSAGE;
      default -> VALIDATION_MESSAGE;
    };
  }

  private ResponseEntity<ErrorResponse> respond(
      ErrorCode code,
      HttpStatusCode status,
      String message,
      boolean retryable,
      ErrorDetails details,
      HttpServletRequest request) {
    ErrorResponse body =
        new ErrorResponse(code, message, retryable, RequestIdFilter.requestIdOf(request), details);
    ResponseEntity.BodyBuilder builder = ResponseEntity.status(status);
    if ((status.value() == HttpStatus.TOO_MANY_REQUESTS.value()
            || status.value() == HttpStatus.SERVICE_UNAVAILABLE.value())
        && details != null
        && details.retryAt() != null) {
      builder.header(HttpHeaders.RETRY_AFTER, Long.toString(secondsUntil(details.retryAt())));
    }
    return builder.body(body);
  }

  private long secondsUntil(Instant retryAt) {
    Duration wait = Duration.between(clock.instant(), retryAt);
    if (wait.isNegative() || wait.isZero()) {
      return 0;
    }
    long seconds = wait.getSeconds();
    return wait.getNano() > 0 ? seconds + 1 : seconds;
  }
}
