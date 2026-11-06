package com.lottery.lottery_service.common.job;

/** lease가 만료됐거나 다른 worker에게 넘어가 저장을 거부했다. 호출한 트랜잭션은 rollback해야 한다. */
public class LeaseLostException extends RuntimeException {

  /** 거부된 job ID로 예외를 만든다. */
  public LeaseLostException(long jobId) {
    super("job lease lost: id=" + jobId);
  }
}
