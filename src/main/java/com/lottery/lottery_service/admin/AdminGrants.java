package com.lottery.lottery_service.admin;

import static com.lottery.lottery_service.common.db.DbValues.bytes;
import static com.lottery.lottery_service.common.db.DbValues.utc;

import com.lottery.lottery_service.admin.mfa.AdminMfaStore;
import com.lottery.lottery_service.common.error.BusinessException;
import com.lottery.lottery_service.common.error.ErrorCode;
import com.lottery.lottery_service.common.job.JobStore;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.function.LongConsumer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 관리자 권한 부여·회수와 다중 요소 인증 초기화(C-33). 첫 ADMIN은 서버 관리 명령({@link #grantByCommand})으로, 그 뒤에는 ADMIN이 사유와
 * 함께 한다. 성공은 같은 트랜잭션에, 거부는 따로 감사 기록에 남긴다. 자기 자신의 역할 변경·권한 회수·인증 초기화는 받지 않는다(마지막 ADMIN이 스스로 잠기는 것을
 * 막는다). 그럴 때는 관리 명령을 쓴다.
 */
@Component
public class AdminGrants {

  private static final String TARGET = "ADMIN_GRANT";

  private final JdbcTemplate jdbc;
  private final TransactionTemplate tx;
  private final AdminAccess access;
  private final AdminAuditLog audit;
  private final AdminMfaStore mfa;
  private final JobStore jobs;
  private final Clock clock;

  /** 구성 요소로 만든다. */
  public AdminGrants(
      JdbcTemplate jdbc,
      PlatformTransactionManager transactionManager,
      AdminAccess access,
      AdminAuditLog audit,
      AdminMfaStore mfa,
      JobStore jobs,
      Clock clock) {
    this.jdbc = jdbc;
    this.tx = new TransactionTemplate(transactionManager);
    this.access = access;
    this.audit = audit;
    this.mfa = mfa;
    this.jobs = jobs;
    this.clock = clock;
  }

  /**
   * 서버 관리 명령으로 권한을 준다. 운영 책임자가 서버에서 실행하므로 화면 권한 확인을 하지 않는다. 감사 기록의 행위자는 대상 회원이다(행위자 컬럼이 회원을 가리켜야
   * 해서).
   *
   * @throws BusinessException 대상 회원이 없거나 ACTIVE가 아니면 {@code NOT_FOUND}
   */
  public void grantByCommand(UUID memberPublicId, AdminRole role, String reason) {
    requireReason(reason);
    tx.executeWithoutResult(
        status -> {
          long target = activeMember(memberPublicId);
          upsertGrant(target, role, null);
          audit.record(event(target, "ADMIN_GRANT_BY_COMMAND", target, reason, role));
          alert("ADMIN_GRANT_BY_COMMAND", role);
        });
  }

  /** ADMIN이 다른 회원에게 권한을 주거나 역할을 바꾼다. 자기 역할은 바꿀 수 없다. */
  public void grant(long actorMemberId, UUID memberPublicId, AdminRole role, String reason) {
    change(
        actorMemberId,
        memberPublicId,
        reason,
        "ADMIN_GRANT",
        true,
        target -> upsertGrant(target, role, actorMemberId),
        role);
  }

  /** ADMIN이 다른 관리자의 권한을 회수한다. 자기 자신은 회수할 수 없다. */
  public void revoke(long actorMemberId, UUID memberPublicId, String reason) {
    change(
        actorMemberId,
        memberPublicId,
        reason,
        "ADMIN_REVOKE",
        true,
        target ->
            requireChanged(
                jdbc.update(
                    "UPDATE admin_grant SET revoked_at = ? WHERE member_id = ? AND revoked_at IS NULL",
                    utc(clock.instant()),
                    target)),
        null);
  }

  /** ADMIN이 다른 관리자의 다중 요소 인증을 초기화한다(기기 분실, 복구 코드 소진). 자기 자신은 할 수 없다. */
  public void resetMfa(long actorMemberId, UUID memberPublicId, String reason) {
    change(
        actorMemberId,
        memberPublicId,
        reason,
        "ADMIN_MFA_RESET",
        true,
        target -> requireChanged(mfa.reset(target)),
        null);
  }

  private void change(
      long actor,
      UUID memberPublicId,
      String reason,
      String action,
      boolean notSelf,
      LongConsumer apply,
      AdminRole role) {
    requireReason(reason);
    try {
      tx.executeWithoutResult(
          status -> {
            access.require(actor, AdminRole.ADMIN);
            long target = activeMember(memberPublicId);
            if (notSelf && target == actor) {
              throw new BusinessException(ErrorCode.FORBIDDEN, "자기 자신에게는 할 수 없습니다.");
            }
            apply.accept(target);
            audit.record(event(actor, action, target, reason, role));
            alert(action, role);
          });
    } catch (BusinessException e) {
      audit.recordIndependently(
          new AdminAuditEvent(
              actor,
              action,
              TARGET,
              memberPublicId.toString(),
              reason,
              null,
              null,
              AdminAuditOutcome.REJECTED));
      throw e;
    }
  }

  /** 운영자에게 사건 종류와 역할만 알린다(07 2.1). 회원 ID와 사유는 넣지 않는다. */
  private void alert(String action, AdminRole role) {
    jobs.enqueue(
        JobStore.OPERATOR_ALERT,
        "admin-change:" + UUID.randomUUID(),
        "{\"kind\":\"ADMIN_CHANGE\",\"action\":\""
            + action
            + "\",\"role\":"
            + (role == null ? "null" : "\"" + role.name() + "\"")
            + "}",
        clock.instant());
  }

  private static void requireChanged(int rows) {
    if (rows != 1) {
      throw new BusinessException(ErrorCode.NOT_FOUND, "관리자 권한이 있는 회원이 아닙니다.");
    }
  }

  private static void requireReason(String reason) {
    if (reason == null || reason.isBlank() || reason.length() > 500) {
      throw new BusinessException(ErrorCode.VALIDATION_FAILED, "사유를 1~500자로 적어 주세요.");
    }
  }

  private void upsertGrant(long target, AdminRole role, Long grantedBy) {
    jdbc.update(
        "INSERT INTO admin_grant (member_id, role, granted_at, granted_by_member_id)"
            + " VALUES (?, ?, ?, ?) ON DUPLICATE KEY UPDATE role = VALUES(role),"
            + " granted_at = VALUES(granted_at), granted_by_member_id ="
            + " VALUES(granted_by_member_id), revoked_at = NULL",
        target,
        role.name(),
        utc(clock.instant()),
        grantedBy);
  }

  private long activeMember(UUID publicId) {
    List<Long> found =
        jdbc.queryForList(
            "SELECT id FROM member WHERE public_id = ? AND state = 'ACTIVE'",
            Long.class,
            (Object) bytes(publicId));
    if (found.isEmpty()) {
      throw new BusinessException(ErrorCode.NOT_FOUND, "회원을 찾을 수 없습니다.");
    }
    return found.get(0);
  }

  private AdminAuditEvent event(
      long actor, String action, long target, String reason, AdminRole role) {
    String after = role == null ? null : "{\"role\":\"" + role.name() + "\"}";
    return new AdminAuditEvent(
        actor,
        action,
        TARGET,
        Long.toString(target),
        reason,
        null,
        after,
        AdminAuditOutcome.SUCCEEDED);
  }
}
