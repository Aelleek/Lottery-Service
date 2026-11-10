package com.lottery.lottery_service.admin;

import com.lottery.lottery_service.common.error.BusinessException;
import com.lottery.lottery_service.common.error.ErrorCode;
import com.lottery.lottery_service.notification.ManualResendService;
import com.lottery.lottery_service.notification.SlotResult;
import com.lottery.lottery_service.result.domain.WinningNumbers;
import com.lottery.lottery_service.result.publish.ResultPublisher;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 첫 출시 관리자 변경(06 4절)의 서버 절차. 요청마다 권한을 다시 확인하고(사유 1~500자 필수), 성공한 변경은 같은 트랜잭션에, 거부·실패는 따로 감사 기록에
 * 남긴다. 관리자 권한이 없는 회원의 시도도 거부로 남긴다(2026-11-09 Q17). 관리자 세션(다중 요소 인증)은 관리자 API 앞의 확인기가 보고, 결과 직접 등록은
 * 확인된 재인증 시각을 받아 5분 안인지만 본다.
 */
@Component
public class AdminOperations {

  /** 결과 직접 등록에 필요한 최근 재인증 기간. */
  public static final Duration REAUTH_WINDOW = Duration.ofMinutes(5);

  private final AdminAccess access;
  private final AdminAuditLog audit;
  private final ResultPublisher publisher;
  private final ManualResendService resend;
  private final TransactionTemplate tx;
  private final Clock clock;

  /** 절차를 만든다. */
  public AdminOperations(
      AdminAccess access,
      AdminAuditLog audit,
      ResultPublisher publisher,
      ManualResendService resend,
      PlatformTransactionManager transactionManager,
      Clock clock) {
    this.access = access;
    this.audit = audit;
    this.publisher = publisher;
    this.resend = resend;
    this.tx = new TransactionTemplate(transactionManager);
    this.clock = clock;
  }

  /**
   * 격리된 결과를 새 게시본으로 승인한다(ADMIN).
   *
   * @throws BusinessException 권한이 없으면 {@code FORBIDDEN}, 사유가 없으면 {@code VALIDATION_FAILED}, 그 밖은
   *     {@link ResultPublisher#approve}와 같다
   */
  public void approveResult(long actor, long resultId, long expectedVersion, String reason) {
    String target = Long.toString(resultId);
    run(
        actor,
        AdminRole.ADMIN,
        "RESULT_PUBLISH",
        "DRAW_RESULT",
        target,
        reason,
        "{\"publicationVersion\":" + expectedVersion + "}",
        () -> {
          publisher.approve(resultId, expectedVersion);
          return "{\"publicationVersion\":" + (expectedVersion + 1) + "}";
        });
  }

  /**
   * 결과 API가 주지 않는 회차의 결과를 직접 등록한다(ADMIN, D-034). 최근 5분 안에 재인증해야 한다.
   *
   * @param reauthenticatedAt 호출한 쪽이 확인한 마지막 재인증 시각. 없으면 {@code null}
   * @return 새 게시본 ID
   * @throws BusinessException 재인증이 없거나 오래됐으면 {@code REAUTH_REQUIRED}. 그 밖은 {@link
   *     ResultPublisher#registerManual}과 같다
   */
  public long registerManualResult(
      long actor,
      int roundNo,
      WinningNumbers winning,
      long expectedVersion,
      Instant reauthenticatedAt,
      String reason) {
    long[] id = new long[1];
    run(
        actor,
        AdminRole.ADMIN,
        "RESULT_MANUAL_REGISTER",
        "ROUND",
        Integer.toString(roundNo),
        reason,
        "{\"publicationVersion\":" + expectedVersion + "}",
        () -> {
          Instant now = clock.instant();
          if (reauthenticatedAt == null
              || reauthenticatedAt.isAfter(now)
              || now.isAfter(reauthenticatedAt.plus(REAUTH_WINDOW))) {
            throw new BusinessException(ErrorCode.REAUTH_REQUIRED, "다시 인증해 주세요.");
          }
          id[0] = publisher.registerManual(roundNo, winning, actor, expectedVersion);
          return "{\"resultId\":" + id[0] + "}";
        });
    return id[0];
  }

  /**
   * 수동 다시 보내기(OPERATOR 이상). 외부 호출이 있어 감사 기록은 실행이 끝난 뒤 따로 남긴다.
   *
   * @throws BusinessException 권한이 없으면 {@code FORBIDDEN}, 기한이 지났으면 {@code DISPATCH_WINDOW_CLOSED}
   */
  public SlotResult resendNotifications(long actor, int roundNo, String reason) {
    AdminAuditEvent pending =
        event(actor, "NOTIFICATION_RESEND", "ROUND", Integer.toString(roundNo), reason, null, null);
    AdminRole role = authorize(actor, AdminRole.OPERATOR, pending);
    try {
      SlotResult result = resend.resend(roundNo, role);
      audit.recordIndependently(
          with(
              pending,
              "{\"failed\":" + result.failed() + ",\"unknown\":" + result.unknown() + "}",
              AdminAuditOutcome.SUCCEEDED));
      return result;
    } catch (BusinessException e) {
      audit.recordIndependently(with(pending, null, AdminAuditOutcome.REJECTED));
      throw e;
    } catch (RuntimeException e) {
      audit.recordIndependently(with(pending, null, AdminAuditOutcome.FAILED));
      throw e;
    }
  }

  private void run(
      long actor,
      AdminRole required,
      String action,
      String targetType,
      String targetKey,
      String reason,
      String beforeJson,
      Supplier<String> change) {
    AdminAuditEvent pending = event(actor, action, targetType, targetKey, reason, beforeJson, null);
    authorize(actor, required, pending);
    try {
      tx.executeWithoutResult(
          status -> audit.record(with(pending, change.get(), AdminAuditOutcome.SUCCEEDED)));
    } catch (BusinessException e) {
      audit.recordIndependently(with(pending, null, AdminAuditOutcome.REJECTED));
      throw e;
    } catch (RuntimeException e) {
      audit.recordIndependently(with(pending, null, AdminAuditOutcome.FAILED));
      throw e;
    }
  }

  private AdminRole authorize(long actor, AdminRole required, AdminAuditEvent pending) {
    try {
      return access.require(actor, required);
    } catch (BusinessException e) {
      audit.recordIndependently(with(pending, null, AdminAuditOutcome.REJECTED));
      throw e;
    }
  }

  private static AdminAuditEvent event(
      long actor,
      String action,
      String targetType,
      String targetKey,
      String reason,
      String beforeJson,
      String afterJson) {
    try {
      return new AdminAuditEvent(
          actor,
          action,
          targetType,
          targetKey,
          reason,
          beforeJson,
          afterJson,
          AdminAuditOutcome.SUCCEEDED);
    } catch (IllegalArgumentException e) {
      throw new BusinessException(ErrorCode.VALIDATION_FAILED, "사유를 1~500자로 적어 주세요.");
    }
  }

  private static AdminAuditEvent with(
      AdminAuditEvent pending, String afterJson, AdminAuditOutcome outcome) {
    return new AdminAuditEvent(
        pending.actorMemberId(),
        pending.action(),
        pending.targetType(),
        pending.targetKey(),
        pending.reason(),
        pending.beforeJson(),
        afterJson,
        outcome);
  }
}
