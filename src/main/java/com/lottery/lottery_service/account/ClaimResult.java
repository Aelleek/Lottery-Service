package com.lottery.lottery_service.account;

/** 비회원 자료 귀속 결과. */
public enum ClaimResult {
  /** 이번에 이 회원에게 귀속했다. */
  CLAIMED,
  /** 이미 이 회원에게 귀속돼 있다. */
  ALREADY_CLAIMED,
  /** 다른 회원에게 귀속됐거나 귀속할 수 없는 주체라 귀속하지 않았다. */
  UNAVAILABLE
}
