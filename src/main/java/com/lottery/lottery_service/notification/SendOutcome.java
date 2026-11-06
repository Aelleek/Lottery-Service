package com.lottery.lottery_service.notification;

/** 제공자 호출 결과(05 8.2절 7번). */
public sealed interface SendOutcome {

  /**
   * 제공자가 접수했다.
   *
   * @param providerMessageId 제공자 메시지 ID
   */
  record Accepted(String providerMessageId) implements SendOutcome {}

  /**
   * 제공자가 받지 않은 것이 확실하다. 다음 슬롯에 다시 시도한다.
   *
   * @param errorCode 제공자 오류 코드
   */
  record NotAccepted(String errorCode) implements SendOutcome {}

  /**
   * 제공자가 확실히 거절했고 다시 보내도 같다.
   *
   * @param errorCode 제공자 오류 코드
   */
  record Rejected(String errorCode) implements SendOutcome {}

  /** 응답을 받지 못해 접수 여부를 모른다. 다시 보내지 않고 대조한다. */
  record Unknown() implements SendOutcome {}
}
