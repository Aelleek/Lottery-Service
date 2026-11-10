package com.lottery.lottery_service.admin;

/** 관리자 역할. ADMIN은 OPERATOR가 할 수 있는 일을 모두 할 수 있다(06 4절). */
public enum AdminRole {
  /** 알림 현황, 수동 다시 보내기, 통계 조회. */
  OPERATOR,
  /** OPERATOR 권한과 결과 승인, 결과 직접 등록. */
  ADMIN;

  /** 이 역할이 {@code required} 역할의 일을 할 수 있는지. */
  public boolean covers(AdminRole required) {
    return ordinal() >= required.ordinal();
  }
}
