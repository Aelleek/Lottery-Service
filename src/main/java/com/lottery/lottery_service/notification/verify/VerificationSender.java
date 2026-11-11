package com.lottery.lottery_service.notification.verify;

import com.lottery.lottery_service.notification.SendOutcome;

/** 인증 코드 메시지 발송(05 8.8절). DB 트랜잭션 밖에서 부른다. 코드는 메시지 행에 남기지 않고 호출 인자로만 넘긴다. */
public interface VerificationSender {

  /**
   * 인증 코드 한 건을 보낸다. 수신 번호는 메시지 행의 암호문에서 읽는다.
   *
   * @param messageId 메시지 ID
   * @param code 여섯 자리 코드
   * @return 호출 결과. 응답을 못 받았으면 {@link SendOutcome.Unknown}
   */
  SendOutcome send(long messageId, String code);
}
