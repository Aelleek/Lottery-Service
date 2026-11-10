package com.lottery.lottery_service.account.withdrawal;

import static com.lottery.lottery_service.common.db.DbValues.uuid;

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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 탈퇴 명령(06 2절 {@code DELETE /me/account}). 최근 5분 안 재인증이 있어야 하고, 같은 요청을 다시 보내면 같은 접수 결과를 준다. 접수하면
 * 접근을 바로 막고 삭제는 job이 한다(202). 재인증 증명을 확인하는 방식은 설계 미정이라(08 6절) 호출한 쪽이 확인한 재인증 시각을 받는다.
 */
@Component
public class WithdrawCommandService {

  /** 탈퇴 명령 종류. */
  public static final String OPERATION = "WITHDRAW";

  /** 필요한 최근 재인증 기간. */
  public static final Duration REAUTH_WINDOW = Duration.ofMinutes(5);

  private final CommandTokenCodec tokens;
  private final CommandOperationStore operations;
  private final WithdrawalService withdrawals;
  private final JdbcTemplate jdbc;
  private final TransactionTemplate tx;
  private final Clock clock;

  /** 서비스를 만든다. */
  public WithdrawCommandService(
      CommandTokenCodec tokens,
      CommandOperationStore operations,
      WithdrawalService withdrawals,
      JdbcTemplate jdbc,
      PlatformTransactionManager transactionManager,
      Clock clock) {
    this.tokens = tokens;
    this.operations = operations;
    this.withdrawals = withdrawals;
    this.jdbc = jdbc;
    this.tx = new TransactionTemplate(transactionManager);
    this.clock = clock;
  }

  /**
   * 탈퇴를 접수한다.
   *
   * @param memberId 세션의 회원 ID
   * @param subjectId 세션의 회원 주체 ID
   * @param subjectPublicId 그 주체의 외부 ID
   * @param expectedAuthVersion 세션이 로그인 때 읽은 회원 인증 버전
   * @param reauthenticatedAt 호출한 쪽이 확인한 마지막 재인증 시각. 없으면 {@code null}
   * @return 멱등 기록 외부 ID(접수 결과 조회용)
   * @throws BusinessException 재인증이 없거나 5분이 지났으면 {@code REAUTH_REQUIRED}, 버전이 다르면 {@code
   *     VERSION_CONFLICT}
   */
  public UUID withdraw(
      long memberId,
      long subjectId,
      UUID subjectPublicId,
      String commandToken,
      UUID idempotencyKey,
      long expectedAuthVersion,
      Instant reauthenticatedAt) {
    CommandToken token = tokens.verify(commandToken, subjectPublicId, OPERATION);
    Instant now = clock.instant();
    if (reauthenticatedAt == null
        || reauthenticatedAt.isAfter(now)
        || now.isAfter(reauthenticatedAt.plus(REAUTH_WINDOW))) {
      throw new BusinessException(ErrorCode.REAUTH_REQUIRED, "다시 인증해 주세요.");
    }
    CommandStart start =
        operations.begin(subjectId, token, idempotencyKey, requestHash(expectedAuthVersion));
    if (start instanceof CommandStart.Completed completed) {
      return completed.publicId();
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
            List<Map<String, Object>> rows =
                jdbc.queryForList(
                    "SELECT public_id, auth_version, state FROM member WHERE id = ? FOR UPDATE",
                    memberId);
            if (rows.isEmpty() || !"ACTIVE".equals(rows.get(0).get("state"))) {
              throw new BusinessException(ErrorCode.NOT_FOUND, "요청한 내용을 찾을 수 없습니다.");
            }
            long version = ((Number) rows.get(0).get("auth_version")).longValue();
            if (version != expectedAuthVersion) {
              throw new BusinessException(
                  ErrorCode.VERSION_CONFLICT,
                  "계정 상태를 다시 확인해 주세요.",
                  ErrorDetails.currentVersion(version));
            }
            withdrawals.block(memberId);
            operations.succeed(started, uuid((byte[]) rows.get(0).get("public_id")), "REQUESTED");
          });
      return started.publicId();
    } catch (BusinessException e) {
      operations.markFailed(started, e.getCode().name());
      throw e;
    } catch (RuntimeException e) {
      operations.markRetryable(started, ErrorCode.TEMPORARILY_UNAVAILABLE.name());
      throw e;
    }
  }

  private static byte[] requestHash(long expectedAuthVersion) {
    try {
      return MessageDigest.getInstance("SHA-256")
          .digest(("withdraw|" + expectedAuthVersion).getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
