package com.lottery.lottery_service.common.job;

/**
 * job 종류 하나를 처리한다. {@link JobRunner}가 연 트랜잭션 안에서 불리고, 그 트랜잭션에서 lease를 확인하며 진행 위치를 저장한다. 같은 걸음을 다시
 * 실행해도 결과가 같아야 한다(C-28).
 */
public interface JobHandler {

  /** 처리하는 job 종류. */
  String type();

  /** 한 걸음을 처리한다. 예외를 던지면 이 걸음의 쓰기는 rollback되고 실패로 남는다. */
  JobStep step(JobLease lease, String cursorJson);
}
