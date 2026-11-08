package com.lottery.lottery_service.notification.verify;

import com.lottery.lottery_service.common.command.CommandOperationStore;
import com.lottery.lottery_service.common.command.CommandStart;
import com.lottery.lottery_service.common.command.CommandToken;
import com.lottery.lottery_service.common.command.CommandTokenCodec;
import com.lottery.lottery_service.common.error.BusinessException;
import com.lottery.lottery_service.common.error.ErrorCode;
import com.lottery.lottery_service.common.error.ErrorDetails;
import com.lottery.lottery_service.recommendation.CommandInProgressException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 알림 수신 동의·철회 명령(06 2절 {@code PUT /me/notification-preference}, 명령 CHANGE_PREFERENCE). 동의는 인증된 번호가
 * 필요하고, 철회는 재인증 없이 되지만 둘 다 화면이 본 연락처 버전과 대조한다(D-059). 같은 요청을 다시 보내면 지금 설정을 준다.
 */
@Component
public class PreferenceCommandService {

  /** 명령 종류. */
  public static final String OPERATION = "CHANGE_PREFERENCE";

  private final CommandTokenCodec tokens;
  private final CommandOperationStore operations;
  private final ConsentStore consents;
  private final JdbcTemplate jdbc;
  private final TransactionTemplate tx;

  /** 서비스를 만든다. */
  public PreferenceCommandService(
      CommandTokenCodec tokens,
      CommandOperationStore operations,
      ConsentStore consents,
      JdbcTemplate jdbc,
      PlatformTransactionManager transactionManager) {
    this.tokens = tokens;
    this.operations = operations;
    this.consents = consents;
    this.jdbc = jdbc;
    this.tx = new TransactionTemplate(transactionManager);
  }

  /**
   * 동의하거나 철회한다.
   *
   * @return 명령 외부 ID
   * @throws BusinessException 연락처 버전이 다르면 {@code CONTACT_CHANGED}, 인증된 번호 없이 동의하면 {@code
   *     VALIDATION_FAILED}
   */
  public UUID change(
      long memberId,
      long subjectId,
      UUID subjectPublicId,
      String commandToken,
      UUID idempotencyKey,
      boolean enabled,
      long expectedVersion,
      String policyVersion) {
    CommandToken token = tokens.verify(commandToken, subjectPublicId, OPERATION);
    CommandStart start =
        operations.begin(
            subjectId, token, idempotencyKey, requestHash(enabled, expectedVersion, policyVersion));
    if (start instanceof CommandStart.Completed done) {
      return done.publicId();
    }
    if (start instanceof CommandStart.InProgress progress) {
      throw new CommandInProgressException(progress.publicId());
    }
    if (start instanceof CommandStart.Failed failed) {
      throw new BusinessException(ErrorCode.valueOf(failed.completionCode()), "요청을 처리하지 못했습니다.");
    }
    CommandStart.Started started = (CommandStart.Started) start;
    try {
      tx.executeWithoutResult(
          status -> {
            if (enabled) {
              consents.grant(memberId, policyVersion, expectedVersion);
            } else {
              requireVersion(memberId, expectedVersion);
              consents.withdraw(memberId, policyVersion);
            }
            operations.succeed(started, subjectPublicId, enabled ? "GRANTED" : "WITHDRAWN");
          });
    } catch (BusinessException e) {
      operations.markFailed(started, e.getCode().name());
      throw e;
    } catch (RuntimeException e) {
      operations.markRetryable(started, ErrorCode.TEMPORARILY_UNAVAILABLE.name());
      throw e;
    }
    return started.publicId();
  }

  private void requireVersion(long memberId, long expectedVersion) {
    List<Long> versions =
        jdbc.queryForList(
            "SELECT version FROM member_contact WHERE member_id = ? FOR UPDATE",
            Long.class,
            memberId);
    long current = versions.isEmpty() ? 0 : versions.get(0);
    if (current != expectedVersion) {
      throw new BusinessException(
          ErrorCode.CONTACT_CHANGED,
          "연락처가 바뀌었습니다. 다시 확인해 주세요.",
          ErrorDetails.currentVersion(current));
    }
  }

  private static byte[] requestHash(boolean enabled, long expectedVersion, String policyVersion) {
    try {
      return MessageDigest.getInstance("SHA-256")
          .digest(
              ("preference|" + enabled + "|" + expectedVersion + "|" + policyVersion)
                  .getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
