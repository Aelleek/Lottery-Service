package com.lottery.lottery_service.recommendation;

/** 생성한 뒤 저장하기 전에 같은 조합이 다른 경로로 이력에 들어갔다. 트랜잭션을 rollback하고 다시 생성한다(05 2절 8번). */
public class RecommendationConflictException extends RuntimeException {

  /** 주체와 회차로 만든다. */
  public RecommendationConflictException(long subjectId, int roundNo) {
    super("recommendation history changed: subject=" + subjectId + ", round=" + roundNo);
  }
}
