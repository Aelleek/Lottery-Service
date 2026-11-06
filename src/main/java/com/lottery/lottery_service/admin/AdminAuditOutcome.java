package com.lottery.lottery_service.admin;

/** 관리자 변경의 결과. 값은 {@code admin_audit_event.outcome} CHECK 제약과 같다. */
public enum AdminAuditOutcome {
  /** 변경이 반영됨. */
  SUCCEEDED,
  /** 권한, 버전, 기간 같은 조건으로 거부됨. */
  REJECTED,
  /** 처리 중 실패. */
  FAILED
}
