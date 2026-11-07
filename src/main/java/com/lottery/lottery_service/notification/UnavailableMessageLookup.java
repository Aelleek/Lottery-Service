package com.lottery.lottery_service.notification;

import org.springframework.stereotype.Component;

/** 발송 제공자가 연결되기 전의 기본 구현. 늘 찾지 못했다고 답한다. 메시지는 UNKNOWN으로 남고 대조 기간이 지나면 종결 규칙으로 끝난다(05 8.3절). */
@Component
public class UnavailableMessageLookup implements MessageLookup {

  @Override
  public ProviderStatus lookup(UnknownMessage message) {
    return new ProviderStatus.NotFound();
  }
}
