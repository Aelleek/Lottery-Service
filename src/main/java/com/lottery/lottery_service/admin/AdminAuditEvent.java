package com.lottery.lottery_service.admin;

import java.util.Objects;

/**
 * 감사 기록 한 건. 전후 값에는 비밀값과 개인정보 원문을 넣지 않는다.
 *
 * @param actorMemberId 변경한 관리자 회원 ID
 * @param action 변경 종류. 예: {@code RESULT_PUBLISH}
 * @param targetType 대상 종류. 예: {@code DRAW_RESULT}
 * @param targetKey 대상 키. 예: 결과 ID
 * @param reason 관리자가 적은 사유. {@link #ACCESS_DENIED}만 {@code null}이다
 * @param beforeJson 변경 전 값 JSON. 없으면 {@code null}
 * @param afterJson 변경 후 값 JSON. 없으면 {@code null}
 * @param outcome 결과
 */
public record AdminAuditEvent(
    long actorMemberId,
    String action,
    String targetType,
    String targetKey,
    String reason,
    String beforeJson,
    String afterJson,
    AdminAuditOutcome outcome) {

  /** 관리자 역할이 없는 회원의 관리자 API 거부(D-062). 사유 없이 남는 유일한 종류다. */
  public static final String ACCESS_DENIED = "ADMIN_ACCESS_DENIED";

  /** 거부 행의 대상 종류. 대상 키는 경로 묶음이다. */
  public static final String ADMIN_API = "ADMIN_API";

  /** 사유는 비울 수 없고 500자 이하다({@link #ACCESS_DENIED}는 사유 없음). 종류와 대상도 비울 수 없다. */
  public AdminAuditEvent {
    Objects.requireNonNull(outcome, "outcome");
    requireText(action, 64, "action");
    requireText(targetType, 32, "targetType");
    requireText(targetKey, 80, "targetKey");
    if (ACCESS_DENIED.equals(action)) {
      if (reason != null) {
        throw new IllegalArgumentException("access denial has no reason");
      }
    } else {
      requireText(reason, 500, "reason");
    }
  }

  /** 관리자 역할이 없는 회원의 관리자 API 거부 행을 만든다. */
  public static AdminAuditEvent accessDenied(long memberId, String route) {
    return new AdminAuditEvent(
        memberId, ACCESS_DENIED, ADMIN_API, route, null, null, null, AdminAuditOutcome.REJECTED);
  }

  private static void requireText(String value, int max, String name) {
    if (value == null || value.isBlank() || value.length() > max) {
      throw new IllegalArgumentException(name + " must be 1.." + max + " characters");
    }
  }
}
