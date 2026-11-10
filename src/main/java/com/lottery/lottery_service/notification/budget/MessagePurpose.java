package com.lottery.lottery_service.notification.budget;

/** 메시지 용도. 값은 {@code delivery_message.purpose}와 같다. */
public enum MessagePurpose {
  /** 결과 기본 알림. */
  RESULT,
  /** 인증 코드. */
  VERIFICATION
}
