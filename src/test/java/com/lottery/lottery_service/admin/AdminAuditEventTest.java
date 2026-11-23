package com.lottery.lottery_service.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AdminAuditEventTest {

  @Test
  @DisplayName("관리자 API 거부 행만 사유가 없고, 사유를 넣으면 거부한다(D-062)")
  void accessDeniedHasNoReason() {
    AdminAuditEvent denied = AdminAuditEvent.accessDenied(1, "/api/v1/admin/stats");
    assertThat(denied.reason()).isNull();
    assertThat(denied.outcome()).isEqualTo(AdminAuditOutcome.REJECTED);

    assertThatThrownBy(
            () ->
                new AdminAuditEvent(
                    1,
                    AdminAuditEvent.ACCESS_DENIED,
                    AdminAuditEvent.ADMIN_API,
                    "/api/v1/admin/stats",
                    "사유",
                    null,
                    null,
                    AdminAuditOutcome.REJECTED))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new AdminAuditEvent(
                    1,
                    "RESULT_PUBLISH",
                    "DRAW_RESULT",
                    "1",
                    null,
                    null,
                    null,
                    AdminAuditOutcome.REJECTED))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
