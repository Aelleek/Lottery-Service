package com.lottery.lottery_service.notification;

/** 발송 제공자 호출. 운영에서는 SOLAPI 알림톡이다. DB 트랜잭션 밖에서 부른다. */
public interface MessageSender {

  /**
   * 결과 알림 한 건을 보낸다. 같은 메시지는 같은 요청 키를 쓴다.
   *
   * @param messageId 메시지 ID
   * @return 호출 결과. 응답을 못 받았으면 {@link SendOutcome.Unknown}
   */
  SendOutcome send(long messageId);
}
