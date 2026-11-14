package com.lottery.lottery_service.notification.verify;

import static com.lottery.lottery_service.common.db.DbValues.instant;

import com.lottery.lottery_service.common.command.CommandOperationStore;
import com.lottery.lottery_service.common.command.CommandStart;
import com.lottery.lottery_service.common.command.CommandToken;
import com.lottery.lottery_service.common.command.CommandTokenCodec;
import com.lottery.lottery_service.common.error.BusinessException;
import com.lottery.lottery_service.common.error.ErrorCode;
import com.lottery.lottery_service.common.error.ErrorDetails;
import com.lottery.lottery_service.common.secret.SecretKeys;
import com.lottery.lottery_service.notification.SendOutcome;
import com.lottery.lottery_service.recommendation.CommandInProgressException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.OptionalLong;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 인증 코드 발급 명령(06 1절 {@code POST /me/notification-verifications}, 명령 REQUEST_VERIFICATION). 한 트랜잭션에서
 * 연락처 버전 확인, 발송 간격·하루 횟수 확인, 인증 요청과 메시지 생성, 인증 코드 몫 예산 예약을 하고, 커밋한 뒤 바로 보낸다(05 8.8절). 제공자가 받지 않은 것이
 * 확실하면 그 요청을 무효로 하고 503으로 답한다. 발송 결과는 처리 권한과 상관없이 따로 커밋하고, 보내기 직전에 처리 권한을 늘려 발송 중 다른 재시도가 넘겨받지 않게
 * 한다. 접수 여부를 모르면 성공으로 답하고 조회로 대조한다. 멱등 기록에는 번호 원문 대신 HMAC만 남긴다.
 */
@Component
public class VerificationIssueService {

  /** 발급 명령 종류. */
  public static final String OPERATION = "REQUEST_VERIFICATION";

  /** 연락처 변경에 필요한 재인증의 유효 시간(02 7.2절). */
  public static final Duration REAUTH_WINDOW = Duration.ofMinutes(5);

  private static final Logger log = LoggerFactory.getLogger(VerificationIssueService.class);

  private final CommandTokenCodec tokens;
  private final CommandOperationStore operations;
  private final VerificationStore verifications;
  private final VerificationMessageStore messages;
  private final VerificationSender sender;
  private final SecretKeys secrets;
  private final JdbcTemplate jdbc;
  private final TransactionTemplate tx;
  private final Clock clock;

  /** 서비스를 만든다. */
  public VerificationIssueService(
      CommandTokenCodec tokens,
      CommandOperationStore operations,
      VerificationStore verifications,
      VerificationMessageStore messages,
      VerificationSender sender,
      SecretKeys secrets,
      JdbcTemplate jdbc,
      PlatformTransactionManager transactionManager,
      Clock clock) {
    this.tokens = tokens;
    this.operations = operations;
    this.verifications = verifications;
    this.messages = messages;
    this.sender = sender;
    this.secrets = secrets;
    this.jdbc = jdbc;
    this.tx = new TransactionTemplate(transactionManager);
    this.clock = clock;
  }

  /**
   * 인증 코드를 발급하고 보낸다.
   *
   * @param phone openapi 형식으로 검사한 국제 번호
   * @param expectedContactVersion 화면이 본 연락처 버전
   * @param reauthenticatedAt 세션의 마지막 재인증 시각. 연락처 변경은 최근 5분 재인증이 필요하다(02 7.2절)
   * @throws BusinessException 재인증이 없거나 5분이 지났으면 {@code REAUTH_REQUIRED}, 연락처 버전이 다르면 {@code
   *     CONTACT_CHANGED}, 간격 전이거나 하루 상한이면 {@code RATE_LIMITED}, 예산이 모자라거나 제공자가 받지 않았으면 {@code
   *     TEMPORARILY_UNAVAILABLE}
   */
  public Requested request(
      long memberId,
      long subjectId,
      UUID subjectPublicId,
      String commandToken,
      UUID idempotencyKey,
      String phone,
      long expectedContactVersion,
      Instant reauthenticatedAt) {
    CommandToken token = tokens.verify(commandToken, subjectPublicId, OPERATION);
    Instant now = clock.instant();
    if (reauthenticatedAt == null
        || reauthenticatedAt.isAfter(now)
        || now.isAfter(reauthenticatedAt.plus(REAUTH_WINDOW))) {
      throw new BusinessException(ErrorCode.REAUTH_REQUIRED, "다시 인증해 주세요.");
    }
    CommandStart start =
        operations.begin(
            subjectId, token, idempotencyKey, requestHash(memberId, phone, expectedContactVersion));
    if (start instanceof CommandStart.Completed done) {
      return replay(done.publicId(), done.resourcePublicId(), memberId);
    }
    if (start instanceof CommandStart.InProgress progress) {
      throw new CommandInProgressException(progress.publicId());
    }
    if (start instanceof CommandStart.Failed failed) {
      throw new BusinessException(ErrorCode.valueOf(failed.completionCode()), "요청을 처리하지 못했습니다.");
    }
    CommandStart.Started started = (CommandStart.Started) start;

    Prepared prepared;
    try {
      prepared = tx.execute(status -> prepare(memberId, phone, expectedContactVersion, started));
    } catch (BusinessException e) {
      if (e.getCode() == ErrorCode.CONTACT_CHANGED) {
        operations.markFailed(started, e.getCode().name());
      } else {
        operations.markRetryable(started, e.getCode().name());
      }
      throw e;
    } catch (RuntimeException e) {
      operations.markRetryable(started, ErrorCode.TEMPORARILY_UNAVAILABLE.name());
      throw e;
    }

    SendOutcome outcome;
    try {
      outcome = sender.send(prepared.messageId(), prepared.challenge().code());
    } catch (RuntimeException e) {
      log.warn(
          "verification send failed without outcome: message={} cause={}",
          prepared.messageId(),
          e.getClass().getSimpleName());
      outcome = new SendOutcome.Unknown();
    }
    SendOutcome result = outcome;
    IssuedChallenge challenge = prepared.challenge();
    String state =
        tx.execute(
            status -> {
              String s = messages.recordOutcome(prepared.messageId(), result);
              if ("FAILED".equals(s)) {
                jdbc.update(
                    "UPDATE verification_challenge SET state = 'REVOKED', code_hmac = NULL"
                        + " WHERE id = ? AND state = 'ACTIVE'",
                    challenge.challengeId());
              }
              return s;
            });
    if ("FAILED".equals(state)) {
      operations.markRetryable(started, ErrorCode.TEMPORARILY_UNAVAILABLE.name());
      throw new BusinessException(
          ErrorCode.TEMPORARILY_UNAVAILABLE,
          "인증 코드를 보내지 못했습니다. 잠시 뒤 다시 시도해 주세요.",
          ErrorDetails.retryAt(resendAt(challenge.expiresAt())));
    }
    try {
      tx.executeWithoutResult(status -> operations.succeed(started, challenge.publicId(), state));
    } catch (IllegalStateException e) {
      log.warn("verification command lease lost after send: message={}", prepared.messageId());
      throw new BusinessException(
          ErrorCode.TEMPORARILY_UNAVAILABLE,
          "요청을 처리하지 못했습니다. 잠시 뒤 다시 시도해 주세요.",
          ErrorDetails.retryAt(resendAt(challenge.expiresAt())));
    }
    return new Requested(
        started.publicId(),
        challenge.publicId(),
        challenge.expiresAt(),
        resendAt(challenge.expiresAt()));
  }

  /**
   * 발급 결과.
   *
   * @param operationId 명령 외부 ID
   * @param challengeId 인증 요청 ID
   * @param expiresAt 코드 만료 시각
   * @param resendAt 다시 요청할 수 있는 가장 이른 시각
   */
  public record Requested(
      UUID operationId, UUID challengeId, Instant expiresAt, Instant resendAt) {}

  private record Prepared(IssuedChallenge challenge, long messageId) {}

  private Prepared prepare(
      long memberId, String phone, long expectedContactVersion, CommandStart.Started started) {
    if (lockedContactVersion(memberId) != expectedContactVersion) {
      throw new BusinessException(ErrorCode.CONTACT_CHANGED, "연락처가 바뀌었습니다. 다시 확인해 주세요.");
    }
    IssuedChallenge challenge = verifications.issue(memberId, phone, expectedContactVersion);
    OptionalLong message = messages.startSending(memberId, challenge);
    if (message.isEmpty()) {
      throw new BusinessException(
          ErrorCode.TEMPORARILY_UNAVAILABLE, "지금은 인증 코드를 보낼 수 없습니다. 잠시 뒤 다시 시도해 주세요.");
    }
    if (!operations.renewLease(started)) {
      throw new IllegalStateException("command lease lost before send: " + started.publicId());
    }
    return new Prepared(challenge, message.getAsLong());
  }

  private Requested replay(UUID operationId, UUID challengeId, long memberId) {
    List<LocalDateTime> expires =
        jdbc.queryForList(
            "SELECT expires_at FROM verification_challenge WHERE public_id = UUID_TO_BIN(?)"
                + " AND member_id = ?",
            LocalDateTime.class,
            challengeId.toString(),
            memberId);
    if (expires.isEmpty()) {
      throw new BusinessException(ErrorCode.RESPONSE_EXPIRED, "이전 요청의 결과가 남아 있지 않습니다.");
    }
    Instant expiresAt = instant(expires.get(0));
    return new Requested(operationId, challengeId, expiresAt, resendAt(expiresAt));
  }

  private static Instant resendAt(Instant expiresAt) {
    return expiresAt.minus(VerificationStore.VALIDITY).plus(VerificationStore.RESEND_GAP);
  }

  private long lockedContactVersion(long memberId) {
    List<Long> versions =
        jdbc.queryForList(
            "SELECT version FROM member_contact WHERE member_id = ? FOR UPDATE",
            Long.class,
            memberId);
    return versions.isEmpty() ? 0 : versions.get(0);
  }

  private byte[] requestHash(long memberId, String phone, long expectedContactVersion) {
    byte[] message =
        ByteBuffer.allocate(16).putLong(memberId).putLong(expectedContactVersion).array();
    byte[] phoneBytes = ("|ISSUE|" + phone).getBytes(StandardCharsets.UTF_8);
    byte[] all = new byte[message.length + phoneBytes.length];
    System.arraycopy(message, 0, all, 0, message.length);
    System.arraycopy(phoneBytes, 0, all, message.length, phoneBytes.length);
    return secrets.verificationCode().sign(all);
  }
}
