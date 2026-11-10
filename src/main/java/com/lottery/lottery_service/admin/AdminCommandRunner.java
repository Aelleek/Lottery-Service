package com.lottery.lottery_service.admin;

import com.lottery.lottery_service.common.command.CommandOperationStore;
import com.lottery.lottery_service.common.command.CommandStart;
import com.lottery.lottery_service.common.command.CommandToken;
import com.lottery.lottery_service.common.error.BusinessException;
import com.lottery.lottery_service.common.error.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 관리자 변경 API의 {@code Idempotency-Key} 처리(06 4절). 관리자 API는 명령 토큰을 받지 않으므로 명령 종류마다 고정한 토큰 ID로 멱등
 * 기록({@code command_operation})을 쓴다. 같은 키·같은 본문은 처음 응답 요약을 돌려주고, 다른 본문은 {@code
 * IDEMPOTENCY_CONFLICT}다. 확정 실패는 같은 실패를 돌려주고, 재인증이 필요했던 실패와 예상하지 못한 오류는 같은 키로 다시 할 수 있다.
 */
@Component
public class AdminCommandRunner {

  /** 같은 키로 다시 보낼 수 있는 기간. 멱등 기록은 그 뒤 7일 더 남는다. */
  public static final Duration KEY_WINDOW = Duration.ofDays(1);

  private final CommandOperationStore operations;
  private final TransactionTemplate tx;
  private final Clock clock;

  /** 실행기를 만든다. */
  public AdminCommandRunner(
      CommandOperationStore operations,
      PlatformTransactionManager transactionManager,
      Clock clock) {
    this.operations = operations;
    this.tx = new TransactionTemplate(transactionManager);
    this.clock = clock;
  }

  /**
   * 관리자 변경을 한 번만 한다.
   *
   * @param subjectId 관리자 회원의 주체 ID
   * @param operation 명령 종류(예: {@code ADMIN_RESULT_PUBLISH})
   * @param key 요청의 {@code Idempotency-Key}
   * @param canonicalRequest 경로 값과 본문을 정해진 순서로 이은 문자열
   * @param transactional 변경과 성공 표시를 한 트랜잭션에서 커밋하면 {@code true}. 외부 호출이 있는 변경은 {@code false}
   * @param change 명령 외부 ID를 받아 변경을 하고 응답 요약(JSON)을 돌려준다
   * @return 응답 요약(JSON)
   */
  public String run(
      long subjectId,
      String operation,
      UUID key,
      String canonicalRequest,
      boolean transactional,
      Function<UUID, String> change) {
    Instant now = clock.instant();
    CommandToken token =
        new CommandToken(
            UUID.nameUUIDFromBytes(("admin|" + operation).getBytes(StandardCharsets.UTF_8)),
            null,
            operation,
            now,
            now.plus(KEY_WINDOW),
            null,
            null);
    CommandStart start = operations.begin(subjectId, token, key, sha256(canonicalRequest));
    if (start instanceof CommandStart.Completed done) {
      return operations
          .summary(done.publicId())
          .orElseThrow(
              () -> new BusinessException(ErrorCode.RESPONSE_EXPIRED, "처음 결과를 찾을 수 없습니다."));
    }
    if (start instanceof CommandStart.Failed failed) {
      throw new BusinessException(failedCode(failed.completionCode()), "처음 요청이 실패했습니다.");
    }
    if (start instanceof CommandStart.InProgress) {
      throw new BusinessException(ErrorCode.TEMPORARILY_UNAVAILABLE, "처리 중입니다. 잠시 뒤 다시 확인해 주세요.");
    }
    CommandStart.Started started = (CommandStart.Started) start;
    String summary;
    try {
      if (transactional) {
        summary =
            tx.execute(
                status -> {
                  String s = change.apply(started.publicId());
                  operations.succeedWithSummary(started, "SUCCEEDED", s);
                  return s;
                });
        return summary;
      }
      summary = change.apply(started.publicId());
    } catch (BusinessException e) {
      if (e.getCode() == ErrorCode.REAUTH_REQUIRED || e.getCode().retryable()) {
        operations.markRetryable(started, e.getCode().name());
      } else {
        operations.markFailed(started, e.getCode().name());
      }
      throw e;
    } catch (RuntimeException e) {
      operations.markRetryable(started, ErrorCode.TEMPORARILY_UNAVAILABLE.name());
      throw e;
    }
    operations.succeedWithSummary(started, "SUCCEEDED", summary);
    return summary;
  }

  private static ErrorCode failedCode(String code) {
    try {
      return ErrorCode.valueOf(code);
    } catch (IllegalArgumentException | NullPointerException e) {
      return ErrorCode.INTERNAL_ERROR;
    }
  }

  private static byte[] sha256(String value) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
