package com.lottery.lottery_service.common.error;

import org.springframework.http.HttpStatus;

/**
 * API 오류 코드. 목록과 뜻은 {@code docs/contracts/openapi.yaml}의 {@code Error.code}와 같다. 화면은 메시지가 아니라 이 코드로
 * 동작을 나눈다.
 */
public enum ErrorCode {
  /** 400·422. 입력 형식이나 업무 규칙 위반. */
  VALIDATION_FAILED(HttpStatus.BAD_REQUEST, false),
  /** 401. 세션이나 비회원 자격이 없거나 만료됨. */
  UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, false),
  /** 403. CSRF 토큰 불일치. */
  CSRF_REJECTED(HttpStatus.FORBIDDEN, false),
  /** 403. Origin·Fetch Metadata 검사 실패. */
  ORIGIN_REJECTED(HttpStatus.FORBIDDEN, false),
  /** 403. 최근 재인증이 필요함. */
  REAUTH_REQUIRED(HttpStatus.FORBIDDEN, false),
  /** 403. 관리자 인증(다중 요소)을 마치지 않았거나 관리자 세션이 끝남. */
  ADMIN_AUTH_REQUIRED(HttpStatus.FORBIDDEN, false),
  /** 403. 권한이나 명령 종류가 허용되지 않음. */
  FORBIDDEN(HttpStatus.FORBIDDEN, false),
  /** 404. 없거나 본인 자원이 아님. */
  NOT_FOUND(HttpStatus.NOT_FOUND, false),
  /** 409. 같은 Idempotency-Key에 다른 본문. */
  IDEMPOTENCY_CONFLICT(HttpStatus.CONFLICT, false),
  /** 409. 화면이 본 버전과 현재 버전이 다름. */
  VERSION_CONFLICT(HttpStatus.CONFLICT, false),
  /** 409. 회차나 일정 버전이 바뀜. */
  ROUND_CHANGED(HttpStatus.CONFLICT, false),
  /** 409. 조건과 이력으로 남은 후보가 5개 미만. */
  CANDIDATES_EXHAUSTED(HttpStatus.CONFLICT, false),
  /** 409. 인증 중 연락처 버전이 바뀜. */
  CONTACT_CHANGED(HttpStatus.CONFLICT, false),
  /** 409. 수동 다시 보내기 기한이 지남. */
  DISPATCH_WINDOW_CLOSED(HttpStatus.CONFLICT, false),
  /** 409. 이미 게시된 결과가 있어 직접 등록할 수 없음. */
  RESULT_ALREADY_PUBLISHED(HttpStatus.CONFLICT, false),
  /** 409. 추첨 전이라 직접 등록할 수 없음. */
  DRAW_NOT_FINISHED(HttpStatus.CONFLICT, false),
  /** 410. 완료된 요청이지만 원본이 지워짐. */
  RESPONSE_EXPIRED(HttpStatus.GONE, false),
  /** 410. 명령 토큰이 만료되고 완료 결과도 없음. */
  COMMAND_EXPIRED(HttpStatus.GONE, false),
  /** 410. 인증 코드가 만료됨. */
  CHALLENGE_EXPIRED(HttpStatus.GONE, false),
  /** 422. 넣을 번호·뺄 번호 조건이 범위를 벗어남. */
  CONDITION_INVALID(HttpStatus.UNPROCESSABLE_ENTITY, false),
  /** 429. 같은 주체의 추천이 생성 중이거나 최소 간격 전. */
  GENERATION_BUSY(HttpStatus.TOO_MANY_REQUESTS, true),
  /** 429. 자격 발급이나 인증 발송 한도 초과. */
  RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, true),
  /** 429. 인증 코드 시도 횟수 초과. */
  ATTEMPTS_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS, false),
  /** 503. 추천 후보 계산이 시간 안에 끝나지 않음. */
  GENERATION_TIMEOUT(HttpStatus.SERVICE_UNAVAILABLE, true),
  /** 503. 회차 일정을 확인할 수 없음. */
  SCHEDULE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, true),
  /** 503. 일시적인 서버·외부 환경 실패. */
  TEMPORARILY_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, true),
  /** 500. 예상하지 못한 서버 오류. 자동으로 다시 보내지 않는다. */
  INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, false);

  private final HttpStatus status;
  private final boolean retryable;

  ErrorCode(HttpStatus status, boolean retryable) {
    this.status = status;
    this.retryable = retryable;
  }

  /** 이 코드가 주로 쓰는 HTTP 상태. */
  public HttpStatus status() {
    return status;
  }

  /** 같은 요청을 나중에 다시 보내도 되는지의 기본값. */
  public boolean retryable() {
    return retryable;
  }
}
