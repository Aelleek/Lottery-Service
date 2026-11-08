package com.lottery.lottery_service.notification.verify;

import com.lottery.lottery_service.common.command.CommandOperationStore;
import com.lottery.lottery_service.common.command.CommandStart;
import com.lottery.lottery_service.common.command.CommandToken;
import com.lottery.lottery_service.common.command.CommandTokenCodec;
import com.lottery.lottery_service.common.error.BusinessException;
import com.lottery.lottery_service.common.error.ErrorCode;
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
 * 인증 코드 확인 명령(06 2절 {@code POST /me/notification-verifications/{id}/verify}, 명령 VERIFY_CONTACT). 시도
 * 수와 잠금은 실패해도 커밋하고(오답 제한 우회 방지), 그 뒤 오류로 답한다. 연락처 버전은 요청 본문이 아니라 지금 저장된 값으로 확인한다. 멱등 기록의 본문 요약에는
 * 코드를 넣지 않는다(다른 코드는 새 키로 보낸다).
 */
@Component
public class VerifyCommandService {

  /** 확인 명령 종류. */
  public static final String OPERATION = "VERIFY_CONTACT";

  private final CommandTokenCodec tokens;
  private final CommandOperationStore operations;
  private final VerificationStore verifications;
  private final JdbcTemplate jdbc;
  private final TransactionTemplate tx;

  /** 서비스를 만든다. */
  public VerifyCommandService(
      CommandTokenCodec tokens,
      CommandOperationStore operations,
      VerificationStore verifications,
      JdbcTemplate jdbc,
      PlatformTransactionManager transactionManager) {
    this.tokens = tokens;
    this.operations = operations;
    this.verifications = verifications;
    this.jdbc = jdbc;
    this.tx = new TransactionTemplate(transactionManager);
  }

  /**
   * 코드를 확인한다.
   *
   * @return 명령 ID와 인증된 연락처의 지금 버전({@code verifiedContactVersion})
   * @throws BusinessException 틀리면 {@code VALIDATION_FAILED}, 잠기면 {@code ATTEMPTS_EXCEEDED}, 만료면
   *     {@code CHALLENGE_EXPIRED}, 연락처가 바뀌었으면 {@code CONTACT_CHANGED}
   */
  public Verified verify(
      long memberId,
      long subjectId,
      UUID subjectPublicId,
      String commandToken,
      UUID idempotencyKey,
      UUID challengeId,
      String code) {
    CommandToken token = tokens.verify(commandToken, subjectPublicId, OPERATION);
    CommandStart start =
        operations.begin(subjectId, token, idempotencyKey, requestHash(challengeId));
    if (start instanceof CommandStart.Completed done) {
      return new Verified(done.publicId(), contactVersion(memberId));
    }
    if (start instanceof CommandStart.InProgress progress) {
      throw new CommandInProgressException(progress.publicId());
    }
    if (start instanceof CommandStart.Failed failed) {
      throw new BusinessException(ErrorCode.valueOf(failed.completionCode()), "요청을 처리하지 못했습니다.");
    }
    CommandStart.Started started = (CommandStart.Started) start;
    VerificationResult result;
    try {
      result =
          tx.execute(
              status -> {
                VerificationResult r =
                    verifications.verify(
                        memberId, challengeId, code, lockedContactVersion(memberId));
                if (r == VerificationResult.VERIFIED) {
                  operations.succeed(started, challengeId, r.name());
                }
                return r;
              });
    } catch (RuntimeException e) {
      operations.markRetryable(started, ErrorCode.TEMPORARILY_UNAVAILABLE.name());
      throw e;
    }
    if (result != VerificationResult.VERIFIED) {
      operations.markFailed(started, result.errorCode().name());
      throw new BusinessException(result.errorCode(), "인증 코드를 확인하지 못했습니다.");
    }
    return new Verified(started.publicId(), contactVersion(memberId));
  }

  /**
   * 확인 결과.
   *
   * @param operationId 명령 외부 ID
   * @param contactVersion 인증된 연락처의 지금 버전
   */
  public record Verified(UUID operationId, long contactVersion) {}

  private long lockedContactVersion(long memberId) {
    List<Long> versions =
        jdbc.queryForList(
            "SELECT version FROM member_contact WHERE member_id = ? FOR UPDATE",
            Long.class,
            memberId);
    return versions.isEmpty() ? 0 : versions.get(0);
  }

  private long contactVersion(long memberId) {
    List<Long> versions =
        jdbc.queryForList(
            "SELECT version FROM member_contact WHERE member_id = ?", Long.class, memberId);
    return versions.isEmpty() ? 0 : versions.get(0);
  }

  private static byte[] requestHash(UUID challengeId) {
    try {
      return MessageDigest.getInstance("SHA-256")
          .digest(("verify|" + challengeId).getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
