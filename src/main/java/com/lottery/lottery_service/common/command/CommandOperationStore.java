package com.lottery.lottery_service.common.command;

import static com.lottery.lottery_service.common.db.DbValues.bytes;
import static com.lottery.lottery_service.common.db.DbValues.instant;
import static com.lottery.lottery_service.common.db.DbValues.utc;
import static com.lottery.lottery_service.common.db.DbValues.uuid;

import com.lottery.lottery_service.common.error.BusinessException;
import com.lottery.lottery_service.common.error.ErrorCode;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 같은 요청을 한 번만 처리하게 하는 멱등 기록({@code command_operation}, 06 1.1절). (주체, 명령, 토큰 ID, 클라이언트 키)가 같으면 같은
 * 요청이다. 보관은 토큰 만료 후 7일이다(02 8절).
 */
@Component
public class CommandOperationStore {

  /** 처리 권한 길이. 이 시간 안에 끝나지 않으면 다른 재시도가 처리를 넘겨받는다. */
  public static final Duration LEASE = Duration.ofSeconds(30);

  /** 동시 첫 등록의 유일키 충돌이나 교착 뒤 다시 시도하는 횟수(04 4절). */
  static final int MAX_RETRIES = 2;

  /** 토큰 만료 뒤 멱등 기록 보관 기간. */
  public static final Duration RETENTION = Duration.ofDays(7);

  private final JdbcTemplate jdbc;
  private final TransactionTemplate tx;
  private final Clock clock;

  /** 저장소를 만든다. */
  public CommandOperationStore(
      JdbcTemplate jdbc, PlatformTransactionManager transactionManager, Clock clock) {
    this.jdbc = jdbc;
    this.tx = new TransactionTemplate(transactionManager);
    this.clock = clock;
  }

  /**
   * 명령을 시작한다. 처음 보는 요청이면 PROCESSING 기록을 만들어 처리를 맡긴다. 자기 트랜잭션에서 바로 커밋한다.
   *
   * @throws BusinessException 같은 키에 다른 본문이면 {@code IDEMPOTENCY_CONFLICT}, 토큰이 만료됐고 완료 결과도 없으면
   *     {@code COMMAND_EXPIRED}
   */
  public CommandStart begin(
      long subjectId, CommandToken token, UUID clientKey, byte[] requestHash) {
    for (int attempt = 0; ; attempt++) {
      try {
        return tx.execute(status -> beginInTransaction(subjectId, token, clientKey, requestHash));
      } catch (DuplicateKeyException | PessimisticLockingFailureException e) {
        if (attempt >= MAX_RETRIES) {
          throw e;
        }
      }
    }
  }

  private CommandStart beginInTransaction(
      long subjectId, CommandToken token, UUID clientKey, byte[] requestHash) {
    Instant now = clock.instant();
    List<Map<String, Object>> rows =
        jdbc.queryForList(
            "SELECT id, public_id, request_hash, state, lease_until, resource_public_id,"
                + " completion_code FROM command_operation WHERE subject_id = ? AND operation = ?"
                + " AND token_id = ? AND client_key = ? FOR UPDATE",
            subjectId,
            token.operation(),
            bytes(token.tokenId()),
            bytes(clientKey));
    if (rows.isEmpty()) {
      if (token.expiredAt(now)) {
        throw expired();
      }
      return insert(subjectId, token, clientKey, requestHash, now);
    }
    Map<String, Object> row = rows.get(0);
    UUID publicId = uuid((byte[]) row.get("public_id"));
    if (!MessageDigest.isEqual((byte[]) row.get("request_hash"), requestHash)) {
      throw new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT, "같은 요청 키로 다른 내용을 보냈습니다.");
    }
    String state = (String) row.get("state");
    switch (state) {
      case "SUCCEEDED":
        return new CommandStart.Completed(
            publicId,
            uuid((byte[]) row.get("resource_public_id")),
            (String) row.get("completion_code"));
      case "FAILED":
        return new CommandStart.Failed(publicId, (String) row.get("completion_code"));
      case "EXPIRED":
        throw expired();
      default:
        break;
    }
    Instant leaseUntil = instant((LocalDateTime) row.get("lease_until"));
    if ("PROCESSING".equals(state) && leaseUntil != null && leaseUntil.isAfter(now)) {
      return new CommandStart.InProgress(publicId);
    }
    if (token.expiredAt(now)) {
      throw expired();
    }
    long id = ((Number) row.get("id")).longValue();
    UUID lease = UUID.randomUUID();
    jdbc.update(
        "UPDATE command_operation SET state = 'PROCESSING', lease_token = ?, lease_until = ?"
            + " WHERE id = ?",
        bytes(lease),
        utc(now.plus(LEASE)),
        id);
    return new CommandStart.Started(id, publicId, lease);
  }

  private CommandStart insert(
      long subjectId, CommandToken token, UUID clientKey, byte[] requestHash, Instant now) {
    UUID publicId = UUID.randomUUID();
    UUID lease = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO command_operation (public_id, subject_id, operation, token_id, client_key,"
            + " request_hash, state, token_expires_at, marker_expires_at, lease_token, lease_until)"
            + " VALUES (?, ?, ?, ?, ?, ?, 'PROCESSING', ?, ?, ?, ?)",
        bytes(publicId),
        subjectId,
        token.operation(),
        bytes(token.tokenId()),
        bytes(clientKey),
        requestHash,
        utc(token.expiresAt()),
        utc(token.expiresAt().plus(RETENTION)),
        bytes(lease),
        utc(now.plus(LEASE)));
    long id =
        jdbc.queryForObject(
            "SELECT id FROM command_operation WHERE public_id = ?", Long.class, bytes(publicId));
    return new CommandStart.Started(id, publicId, lease);
  }

  /**
   * 업무 트랜잭션 안에서 명령을 성공으로 끝낸다. 업무 저장과 함께 커밋되므로, 저장이 rollback되면 이 기록도 PROCESSING으로 남는다.
   *
   * @throws IllegalStateException 처리 권한을 다른 재시도가 넘겨받았을 때. 업무 트랜잭션을 rollback해야 한다
   */
  public void succeed(CommandStart.Started started, UUID resourcePublicId, String completionCode) {
    int changed =
        jdbc.update(
            "UPDATE command_operation SET state = 'SUCCEEDED', resource_public_id = ?,"
                + " completion_code = ?, completed_at = ?, lease_token = NULL, lease_until = NULL"
                + " WHERE id = ? AND state = 'PROCESSING' AND lease_token = ?",
            bytes(resourcePublicId),
            completionCode,
            utc(clock.instant()),
            started.operationId(),
            bytes(started.leaseToken()));
    if (changed != 1) {
      throw new IllegalStateException("command lease lost: " + started.publicId());
    }
  }

  /** 다시 시도하면 될 실패로 남긴다. 같은 요청이 다시 오면 처리를 다시 맡는다. 자기 트랜잭션에서 커밋한다. */
  public void markRetryable(CommandStart.Started started, String code) {
    finish(started, "RETRYABLE", code);
  }

  /** 확정 실패로 남긴다. 같은 요청이 다시 오면 같은 실패를 돌려준다. 자기 트랜잭션에서 커밋한다. */
  public void markFailed(CommandStart.Started started, String code) {
    finish(started, "FAILED", code);
  }

  private void finish(CommandStart.Started started, String state, String code) {
    tx.executeWithoutResult(
        status ->
            jdbc.update(
                "UPDATE command_operation SET state = ?, completion_code = ?, completed_at = ?,"
                    + " lease_token = NULL, lease_until = NULL WHERE id = ? AND state = 'PROCESSING'"
                    + " AND lease_token = ?",
                state,
                code,
                utc(clock.instant()),
                started.operationId(),
                bytes(started.leaseToken())));
  }

  private static BusinessException expired() {
    return new BusinessException(ErrorCode.COMMAND_EXPIRED, "요청이 만료됐습니다. 화면을 새로 고쳐 다시 시도해 주세요.");
  }
}
