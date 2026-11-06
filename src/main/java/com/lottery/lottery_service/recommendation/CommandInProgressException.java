package com.lottery.lottery_service.recommendation;

import java.util.UUID;

/** 같은 요청이 아직 처리 중이다. 화면에는 202와 조회 경로를 준다. */
public class CommandInProgressException extends RuntimeException {

  private final UUID operationPublicId;

  /** 처리 중인 멱등 기록 외부 ID로 만든다. */
  public CommandInProgressException(UUID operationPublicId) {
    super("command in progress");
    this.operationPublicId = operationPublicId;
  }

  /** 처리 중인 멱등 기록 외부 ID. */
  public UUID operationPublicId() {
    return operationPublicId;
  }
}
