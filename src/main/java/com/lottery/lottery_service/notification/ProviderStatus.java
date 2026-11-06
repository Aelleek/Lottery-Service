package com.lottery.lottery_service.notification;

/** 제공자 메시지 조회로 확인한 상태(C-27). */
public sealed interface ProviderStatus {

  /**
   * 접수됐다.
   *
   * @param providerMessageId 제공자 메시지 ID
   */
  record Accepted(String providerMessageId) implements ProviderStatus {}

  /**
   * 전달됐다.
   *
   * @param providerMessageId 제공자 메시지 ID
   */
  record Delivered(String providerMessageId) implements ProviderStatus {}

  /**
   * 제공자가 확실히 실패로 처리했다.
   *
   * @param errorCode 제공자 오류 코드
   */
  record Failed(String errorCode) implements ProviderStatus {}

  /** 조회에서 찾지 못했다. 이것만으로 미접수로 확정하지 않는다. */
  record NotFound() implements ProviderStatus {}
}
