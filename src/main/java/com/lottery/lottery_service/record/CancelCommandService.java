package com.lottery.lottery_service.record;

import static com.lottery.lottery_service.common.db.DbValues.bytes;

import com.lottery.lottery_service.common.command.CommandOperationStore;
import com.lottery.lottery_service.common.command.CommandResultLoader;
import com.lottery.lottery_service.common.command.CommandStart;
import com.lottery.lottery_service.common.command.CommandToken;
import com.lottery.lottery_service.common.command.CommandTokenCodec;
import com.lottery.lottery_service.common.db.DbValues;
import com.lottery.lottery_service.common.error.BusinessException;
import com.lottery.lottery_service.common.error.ErrorCode;
import com.lottery.lottery_service.recommendation.CommandInProgressException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 구매 등록 취소 명령을 멱등하게 처리한다(05 2절 취소 3번, 06 1.1절). 같은 토큰·키·본문을 다시 보내면 기록을 다시 보지 않고 번호 없는 완료 증명만 돌려준다.
 * 그래서 그 사이 다시 구매 등록한 기록을 옛 취소 요청이 취소하지 않는다. 확인 순서는 토큰의 주체·명령, 멱등 기록(완료 여부), 기록의 소유권·버전이다.
 */
@Component
public class CancelCommandService implements CommandResultLoader {

  /** 취소 명령 종류. 명령 토큰에 묶인다. */
  public static final String OPERATION = "CANCEL_PURCHASE";

  /** 추천 기록으로 남았다. */
  public static final String RETAINED = "RECOMMENDATION_RETAINED";

  /** 직접 등록 기록이라 숨겼다. */
  public static final String DELETED = "RECORD_DELETED";

  private static final Set<ErrorCode> FINAL_FAILURES =
      EnumSet.of(ErrorCode.NOT_FOUND, ErrorCode.VERSION_CONFLICT, ErrorCode.FORBIDDEN);

  private final CommandTokenCodec tokens;
  private final CommandOperationStore operations;
  private final PurchaseStore purchases;
  private final JdbcTemplate jdbc;
  private final TransactionTemplate tx;

  /** 서비스를 만든다. */
  public CancelCommandService(
      CommandTokenCodec tokens,
      CommandOperationStore operations,
      PurchaseStore purchases,
      JdbcTemplate jdbc,
      PlatformTransactionManager transactionManager) {
    this.tokens = tokens;
    this.operations = operations;
    this.purchases = purchases;
    this.jdbc = jdbc;
    this.tx = new TransactionTemplate(transactionManager);
  }

  /**
   * 취소 명령을 처리한다.
   *
   * @param subjectId 세션에서 구한 주체 ID
   * @param subjectPublicId 그 주체의 외부 ID
   * @param commandToken {@code X-Command-Token}
   * @param idempotencyKey {@code Idempotency-Key}
   * @param recordPublicId 경로의 기록 외부 ID
   * @param expectedVersion 본문의 {@code expectedVersion}
   * @throws CommandInProgressException 같은 요청이 처리 중일 때
   */
  public CancelProof cancel(
      long subjectId,
      UUID subjectPublicId,
      String commandToken,
      UUID idempotencyKey,
      UUID recordPublicId,
      long expectedVersion) {
    CommandToken token = tokens.verify(commandToken, subjectPublicId, OPERATION);
    CommandStart start =
        operations.begin(
            subjectId, token, idempotencyKey, requestHash(recordPublicId, expectedVersion));
    if (start instanceof CommandStart.Completed completed) {
      return new CancelProof(
          completed.publicId(), completed.completionCode(), completedAt(completed.publicId()));
    }
    if (start instanceof CommandStart.InProgress progress) {
      throw new CommandInProgressException(progress.publicId());
    }
    if (start instanceof CommandStart.Failed failed) {
      throw new BusinessException(ErrorCode.valueOf(failed.completionCode()), "요청을 처리하지 못했습니다.");
    }
    CommandStart.Started started = (CommandStart.Started) start;
    try {
      String outcome =
          tx.execute(
              status -> {
                PurchaseStore.CancelOutcome result =
                    purchases.cancelPurchase(subjectId, recordPublicId, expectedVersion);
                String code = result.recordDeleted() ? DELETED : RETAINED;
                operations.succeed(started, recordPublicId, code);
                return code;
              });
      return new CancelProof(started.publicId(), outcome, completedAt(started.publicId()));
    } catch (BusinessException e) {
      if (FINAL_FAILURES.contains(e.getCode())) {
        operations.markFailed(started, e.getCode().name());
      } else {
        operations.markRetryable(started, e.getCode().name());
      }
      throw e;
    } catch (RuntimeException e) {
      operations.markRetryable(started, ErrorCode.TEMPORARILY_UNAVAILABLE.name());
      throw e;
    }
  }

  @Override
  public String operation() {
    return OPERATION;
  }

  /** 끝난 취소 명령의 번호 없는 완료 증명. */
  @Override
  public CancelProof load(
      long subjectId, UUID operationPublicId, UUID resourcePublicId, String completionCode) {
    return new CancelProof(operationPublicId, completionCode, completedAt(operationPublicId));
  }

  private Instant completedAt(UUID operationPublicId) {
    LocalDateTime at =
        jdbc.queryForObject(
            "SELECT completed_at FROM command_operation WHERE public_id = ?",
            LocalDateTime.class,
            bytes(operationPublicId));
    return DbValues.instant(at);
  }

  static byte[] requestHash(UUID recordPublicId, long expectedVersion) {
    try {
      return MessageDigest.getInstance("SHA-256")
          .digest((recordPublicId + "|" + expectedVersion).getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * 번호 없는 완료 증명(openapi {@code CancelData}).
   *
   * @param operationId 멱등 기록 외부 ID
   * @param outcome {@code RECOMMENDATION_RETAINED} 또는 {@code RECORD_DELETED}
   * @param completedAt 처음 처리한 시각
   */
  public record CancelProof(UUID operationId, String outcome, Instant completedAt) {}
}
