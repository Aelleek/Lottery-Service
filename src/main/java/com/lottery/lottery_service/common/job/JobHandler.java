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

  /**
   * 걸음을 실행기의 트랜잭션 안에서 부를지. 외부 호출을 하는 처리기(결과 수집, 발송 슬롯)는 {@code false}로 두고 스스로 짧은 트랜잭션을 연다. 그때 실행기는
   * 걸음이 끝난 뒤 따로 lease를 확인하며 진행 위치를 저장한다.
   */
  default boolean transactional() {
    return true;
  }
}
