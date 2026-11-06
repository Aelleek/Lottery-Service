package com.lottery.lottery_service.common.job;

/** job 상태. 값은 {@code job.state} CHECK 제약과 같다. */
public enum JobState {
  /** 처리 대기. */
  READY,
  /** worker가 lease를 쥐고 처리 중. */
  RUNNING,
  /** 처리 완료. */
  SUCCEEDED,
  /** 실패나 lease 만료 뒤 다음 시도 시각까지 대기. */
  RETRY_WAIT,
  /** 시도 상한 초과. 운영자 확인이 필요하다. */
  DEAD,
  /** 취소됨. */
  CANCELED
}
