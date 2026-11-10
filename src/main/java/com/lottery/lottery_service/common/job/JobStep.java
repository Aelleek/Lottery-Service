package com.lottery.lottery_service.common.job;

/** job 처리 한 걸음의 결과. */
public sealed interface JobStep {

  /**
   * 일부를 처리했다. 진행 위치를 저장하고 이어서 처리한다.
   *
   * @param cursorJson 다음 걸음에 넘길 진행 위치 JSON
   */
  record Continue(String cursorJson) implements JobStep {}

  /** 모두 처리했다. */
  record Done() implements JobStep {}
}
