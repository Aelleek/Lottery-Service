package com.lottery.lottery_service.notification;

import org.springframework.stereotype.Component;

/**
 * 발송 제공자가 연결되기 전의 기본 구현. 보내지 않고 확정 미접수로 답해 비용 예약을 돌려주고 다음 슬롯에 다시 시도하게 한다. 템플릿 심사와 SOLAPI 설정이 끝나기
 * 전에는 보내지 않는다(02 6.5절).
 */
@Component
public class UnavailableMessageSender implements MessageSender {

  @Override
  public SendOutcome send(long messageId) {
    return new SendOutcome.NotAccepted("PROVIDER_NOT_CONFIGURED");
  }
}
