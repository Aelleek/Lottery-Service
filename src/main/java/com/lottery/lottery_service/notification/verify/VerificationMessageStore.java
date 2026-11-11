package com.lottery.lottery_service.notification.verify;

import static com.lottery.lottery_service.common.db.DbValues.utc;

import com.lottery.lottery_service.notification.SendOutcome;
import com.lottery.lottery_service.notification.budget.MessageBudgetStore;
import com.lottery.lottery_service.notification.budget.MessagePurpose;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.OptionalLong;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 인증 코드 메시지 행(용도 VERIFICATION, 05 8.8절). 호출한 쪽 트랜잭션 안에서 부른다. 코드 본문은 행에 두지 않는다. 수신 번호 암호문은 인증 요청의 것을
 * 복사하고, 비용은 인증 코드 몫에서 예약한다. 다시 보내지 않으므로 확실한 미접수도 거절처럼 끝낸다.
 */
@Component
public class VerificationMessageStore {

  /** 인증 코드 한 건의 비용(0.1원 단위). 기본 알림과 같은 알림톡 단가다. */
  public static final long COST_UNITS = 143;

  /** IN_FLIGHT로 남은 인증 메시지를 접수 여부 모름으로 넘기는 시간. 요청 처리 중 멈춘 경우다. */
  public static final Duration STALE_IN_FLIGHT = Duration.ofMinutes(5);

  private final JdbcTemplate jdbc;
  private final MessageBudgetStore budget;
  private final Clock clock;
  private final SecureRandom random = new SecureRandom();

  /** 저장소를 만든다. */
  public VerificationMessageStore(JdbcTemplate jdbc, MessageBudgetStore budget, Clock clock) {
    this.jdbc = jdbc;
    this.budget = budget;
    this.clock = clock;
  }

  /**
   * 인증 요청의 메시지를 만들고 비용을 예약해 IN_FLIGHT로 둔다. 커밋한 뒤 제공자를 부른다.
   *
   * @return 메시지 ID. 예산이 모자라면 빈 값
   */
  public OptionalLong startSending(long memberId, IssuedChallenge challenge) {
    byte[] requestKey = new byte[16];
    random.nextBytes(requestKey);
    UUID publicId = UUID.randomUUID();
    Instant now = clock.instant();
    jdbc.update(
        "INSERT INTO delivery_message (public_id, purpose, member_id, challenge_id, state,"
            + " provider_request_key, recipient_ciphertext, expires_at)"
            + " SELECT UUID_TO_BIN(?), 'VERIFICATION', member_id, id, 'PENDING', ?,"
            + " target_ciphertext, expires_at FROM verification_challenge WHERE id = ?"
            + " AND member_id = ?",
        publicId.toString(),
        requestKey,
        challenge.challengeId(),
        memberId);
    long id =
        jdbc.queryForObject(
            "SELECT id FROM delivery_message WHERE public_id = UUID_TO_BIN(?)",
            Long.class,
            publicId.toString());
    if (!budget.reserve(id, MessagePurpose.VERIFICATION, COST_UNITS, now)) {
      return OptionalLong.empty();
    }
    jdbc.update(
        "UPDATE delivery_message SET state = 'IN_FLIGHT', attempt_count = 1, last_attempt_at = ?,"
            + " version = version + 1 WHERE id = ? AND state = 'PENDING'",
        utc(now),
        id);
    return OptionalLong.of(id);
  }

  /**
   * 제공자 호출 결과를 남긴다. IN_FLIGHT일 때만 바꾼다. 접수는 비용 확정, 미접수·거절은 실패와 반환, 모름은 예약을 둔 채 조회로 대조한다.
   *
   * @return 바뀐 상태. IN_FLIGHT가 아니었으면 지금 상태
   */
  public String recordOutcome(long messageId, SendOutcome outcome) {
    String state;
    String providerId = null;
    String error = null;
    if (outcome instanceof SendOutcome.Accepted a) {
      state = "ACCEPTED";
      providerId = a.providerMessageId();
    } else if (outcome instanceof SendOutcome.NotAccepted n) {
      state = "FAILED";
      error = n.errorCode();
    } else if (outcome instanceof SendOutcome.Rejected r) {
      state = "FAILED";
      error = r.errorCode();
    } else {
      state = "UNKNOWN";
    }
    int changed =
        jdbc.update(
            "UPDATE delivery_message SET state = ?, provider_message_id = ?, last_error_code = ?,"
                + " finalized_at = IF(? = 'FAILED', ?, NULL), version = version + 1"
                + " WHERE id = ? AND purpose = 'VERIFICATION' AND state = 'IN_FLIGHT'",
            state,
            providerId,
            error,
            state,
            utc(clock.instant()),
            messageId);
    if (changed != 1) {
      return jdbc.queryForObject(
          "SELECT state FROM delivery_message WHERE id = ?", String.class, messageId);
    }
    if ("ACCEPTED".equals(state)) {
      budget.spend(messageId);
    } else if ("FAILED".equals(state)) {
      budget.release(messageId);
    }
    return state;
  }

  /**
   * 요청 처리 중 멈춰 IN_FLIGHT로 남은 인증 메시지를 UNKNOWN으로 바꿔 조회 대조에 넘긴다(05 8.3절). 다시 보내지 않는다.
   *
   * @return 바꾼 수
   */
  public int markStaleInFlightUnknown() {
    return jdbc.update(
        "UPDATE delivery_message SET state = 'UNKNOWN', version = version + 1"
            + " WHERE purpose = 'VERIFICATION' AND state = 'IN_FLIGHT' AND last_attempt_at < ?",
        utc(clock.instant().minus(STALE_IN_FLIGHT)));
  }
}
