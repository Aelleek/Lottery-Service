package com.lottery.lottery_service.record;

/** 구매 등록 항목 하나의 결과(05 3절). */
public enum PurchaseOutcome {
  /** 새 기록을 만들었거나 지운 직접 등록 기록을 되살렸다. */
  CREATED,
  /** 추천만 받은 기록에 구매 표시를 붙였다. */
  CONVERTED,
  /** 이미 구매 등록된 조합이라 그대로 두었다. */
  ALREADY_REGISTERED
}
