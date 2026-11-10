package com.lottery.lottery_service.result.publish;

/** 수집한 결과를 받았을 때의 처리(05 6절 3번). */
public enum ReceiveOutcome {
  /** 처음 받은 결과라 게시했다. */
  PUBLISHED,
  /** 게시한 결과와 의미가 같다. 새 게시본을 만들지 않았다. */
  SAME_AS_PUBLISHED,
  /** 게시한 결과와 달라 격리하고 운영자 알림을 등록했다. */
  QUARANTINED,
  /** 이미 격리한 결과와 같다. 받은 시각만 갱신했다. */
  ALREADY_QUARANTINED
}
