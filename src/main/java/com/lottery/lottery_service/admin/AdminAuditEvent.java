package com.lottery.lottery_service.admin;

import java.util.Objects;

/**
 * 감사 기록 한 건. 전후 값에는 비밀값과 개인정보 원문을 넣지 않는다.
 *
 * @param actorMemberId 변경한 관리자 회원 ID
 * @param action 변경 종류. 예: {@code RESULT_PUBLISH}
 * @param targetType 대상 종류. 예: {@code DRAW_RESULT}
 * @param targetKey 대상 키. 예: 결과 ID
 * @param reason 관리자가 적은 사유
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

  /** 사유는 비울 수 없고 500자 이하다. 종류와 대상도 비울 수 없다. */
  public AdminAuditEvent {
    Objects.requireNonNull(outcome, "outcome");
    requireText(action, 64, "action");
    requireText(targetType, 32, "targetType");
    requireText(targetKey, 80, "targetKey");
    requireText(reason, 500, "reason");
  }

  private static void requireText(String value, int max, String name) {
    if (value == null || value.isBlank() || value.length() > max) {
      throw new IllegalArgumentException(name + " must be 1.." + max + " characters");
    }
  }
}
