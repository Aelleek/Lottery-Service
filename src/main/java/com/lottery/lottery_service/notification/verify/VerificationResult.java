package com.lottery.lottery_service.notification.verify;

import com.lottery.lottery_service.common.error.ErrorCode;

/** 인증 코드 확인 결과. 실패도 예외가 아니라 결과로 돌려준다. 시도 수와 잠금이 같은 트랜잭션에서 커밋돼야 해서다(rollback되면 오답 제한을 우회할 수 있다). */
public enum VerificationResult {
  /** 맞았다. 번호가 인증됐다. */
  VERIFIED(null),
  /** 틀렸다. 남은 시도가 있다. */
  WRONG(ErrorCode.VALIDATION_FAILED),
  /** 오답이 상한에 닿아 잠겼다. */
  LOCKED(ErrorCode.ATTEMPTS_EXCEEDED),
  /** 만료됐거나 다시 발급돼 무효다. */
  EXPIRED(ErrorCode.CHALLENGE_EXPIRED),
  /** 인증 중 연락처 버전이 바뀌었다. */
  CONTACT_CHANGED(ErrorCode.CONTACT_CHANGED);

  private final ErrorCode errorCode;

  VerificationResult(ErrorCode errorCode) {
    this.errorCode = errorCode;
  }

  /** 화면에 돌려줄 오류 코드. 성공이면 {@code null}. 커밋한 뒤에 오류로 바꾼다. */
  public ErrorCode errorCode() {
    return errorCode;
  }
}
