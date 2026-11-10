package com.lottery.lottery_service.notification;

import java.time.Instant;

/** 접수 여부를 모르는 메시지를 제공자 메시지 조회로 찾는다(05 8.3절, C-27). 트랜잭션 밖에서 부른다. */
public interface MessageLookup {

  /**
   * 메시지 하나의 제공자 상태를 조회한다.
   *
   * @param message 조회할 메시지
   * @return 확인한 상태. 찾지 못했으면 {@link ProviderStatus.NotFound}
   */
  ProviderStatus lookup(UnknownMessage message);

  /**
   * 조회에 쓰는 메시지 정보.
   *
   * @param id 메시지 ID
   * @param providerRequestKey 호출 때 {@code customFields}에 넣은 요청 키
   * @param providerMessageId 제공자 메시지 ID. 모르면 {@code null}
   * @param lastAttemptAt 마지막 호출 시각. 수신 번호와 시각 범위로 찾을 때 쓴다
   */
  record UnknownMessage(
      long id, byte[] providerRequestKey, String providerMessageId, Instant lastAttemptAt) {}
}
