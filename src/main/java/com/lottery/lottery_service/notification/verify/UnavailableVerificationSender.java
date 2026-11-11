package com.lottery.lottery_service.notification.verify;

import com.lottery.lottery_service.notification.SendOutcome;
import org.springframework.stereotype.Component;

/** 발송 제공자가 연결되기 전의 기본 구현. 인증 템플릿 심사와 SOLAPI 설정이 끝나기 전에는 보내지 않는다(02 6.5절). */
@Component
public class UnavailableVerificationSender implements VerificationSender {

  @Override
  public SendOutcome send(long messageId, String code) {
    return new SendOutcome.NotAccepted("PROVIDER_NOT_CONFIGURED");
  }
}
