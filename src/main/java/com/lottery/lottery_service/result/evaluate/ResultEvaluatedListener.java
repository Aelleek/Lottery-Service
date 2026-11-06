package com.lottery.lottery_service.result.evaluate;

/** 게시본 판정 job이 그 회차 기록을 끝까지 판정했을 때 알림을 받는다. 판정 job의 마지막 걸음과 같은 트랜잭션에서 불린다. */
public interface ResultEvaluatedListener {

  /** 게시본 판정이 끝났다. */
  void evaluated(long resultId);
}
