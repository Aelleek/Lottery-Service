package com.lottery.lottery_service.notification;

import static com.lottery.lottery_service.common.db.DbValues.bytes;
import static com.lottery.lottery_service.common.db.DbValues.utc;

import com.lottery.lottery_service.notification.budget.MessageBudgetStore;
import com.lottery.lottery_service.notification.budget.MessagePurpose;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 결과 기본 알림 메시지의 상태를 바꾼다(05 8.1~8.2절). 회원·회차당 한 행이고, 상태는 뒤로 돌아가지 않는다. 비용 상태와 예산 카운터는 같은 트랜잭션에서 함께
 * 바꾼다. 호출한 쪽 트랜잭션 안에서 부르고, 제공자 호출은 IN_FLIGHT를 커밋한 뒤 트랜잭션 밖에서 한다.
 */
@Component
public class ResultMessageStore {

  /** 기본 알림의 유효기간(결과 게시 후). 다시 보내기 기한과 같다. */
  public static final Duration VALIDITY = Duration.ofDays(7);

  private final JdbcTemplate jdbc;
  private final NotificationTargetQuery targets;
  private final MessageBudgetStore budget;
  private final Clock clock;

  /** 저장소를 만든다. */
  public ResultMessageStore(
      JdbcTemplate jdbc, NotificationTargetQuery targets, MessageBudgetStore budget, Clock clock) {
    this.jdbc = jdbc;
    this.targets = targets;
    this.budget = budget;
    this.clock = clock;
  }

  /**
   * 대상인데 아직 메시지가 없는 회원에게 PENDING 메시지를 만든다. 회원·회차 유일 제약으로 두 번 생기지 않는다.
   *
   * @return 새로 만든 메시지 수
   */
  public int createMissing(int roundNo) {
    Instant published =
        jdbc.queryForObject(
                "SELECT d.published_at FROM lotto_round r JOIN draw_result d ON d.id = r.published_result_id"
                    + " WHERE r.round_no = ?",
                LocalDateTime.class,
                roundNo)
            .toInstant(ZoneOffset.UTC);
    int created = 0;
    for (NotificationTarget target : targets.targets(roundNo)) {
      if (insertIfAbsent(target.memberId(), roundNo, published.plus(VALIDITY))) {
        created++;
      }
    }
    return created;
  }

  private boolean insertIfAbsent(long memberId, int roundNo, Instant expiresAt) {
    try {
      jdbc.update(
          "INSERT INTO delivery_message (public_id, purpose, member_id, round_no, state,"
              + " provider_request_key, expires_at) VALUES (?, 'RESULT', ?, ?, 'PENDING', ?, ?)",
          bytes(UUID.randomUUID()),
          memberId,
          roundNo,
          bytes(UUID.randomUUID()),
          utc(expiresAt));
      return true;
    } catch (DuplicateKeyException e) {
      return false;
    }
  }

  /**
   * 보내기 직전에 자격을 다시 확인한다(05 8.2절 4번). 회원이 활성 상태가 아니거나, 알림 수신이 꺼졌거나, 더는 대상 기록이 없으면 SUPPRESSED로 바꾼다.
   *
   * @return 보내도 되면 {@code true}
   */
  public boolean recheck(long messageId) {
    List<Map<String, Object>> rows =
        jdbc.queryForList(
            "SELECT member_id, round_no FROM delivery_message WHERE id = ?"
                + " AND state IN ('PENDING','RETRY_WAIT') FOR UPDATE",
            messageId);
    if (rows.isEmpty()) {
      return false;
    }
    long member = ((Number) rows.get(0).get("member_id")).longValue();
    int round = ((Number) rows.get(0).get("round_no")).intValue();
    Boolean eligible =
        jdbc.queryForObject(
            "SELECT m.state = 'ACTIVE' AND COALESCE(c.notify_enabled, FALSE)"
                + " FROM member m LEFT JOIN member_contact c ON c.member_id = m.id WHERE m.id = ?",
            Boolean.class,
            member);
    Set<Long> stillTargets =
        targets.targets(round).stream()
            .map(NotificationTarget::memberId)
            .collect(Collectors.toSet());
    if (Boolean.TRUE.equals(eligible) && stillTargets.contains(member)) {
      return true;
    }
    jdbc.update(
        "UPDATE delivery_message SET state = 'SUPPRESSED', finalized_at = ?, version = version + 1"
            + " WHERE id = ?",
        utc(clock.instant()),
        messageId);
    return false;
  }

  /**
   * 비용을 예약하고 IN_FLIGHT로 바꾼다. 이때 보낼 내용의 안내 요약({@link CommunicatedSummary})을 남긴다. 커밋한 뒤 제공자를 부른다.
   *
   * @return 예약했으면 {@code true}, 예산이 모자라면 상태를 바꾸지 않고 {@code false}
   */
  public boolean startSending(long messageId, long costUnits) {
    Instant now = clock.instant();
    if (!budget.reserve(messageId, MessagePurpose.RESULT, costUnits, now)) {
      return false;
    }
    Map<String, Object> message =
        jdbc.queryForMap(
            "SELECT member_id, round_no FROM delivery_message WHERE id = ?", messageId);
    long member = ((Number) message.get("member_id")).longValue();
    int round = ((Number) message.get("round_no")).intValue();
    NotificationTarget target =
        targets.targets(round).stream()
            .filter(t -> t.memberId() == member)
            .findFirst()
            .orElse(null);
    int changed =
        jdbc.update(
            "UPDATE delivery_message SET state = 'IN_FLIGHT', attempt_count = attempt_count + 1,"
                + " last_attempt_at = ?, communicated_summary = ?, version = version + 1"
                + " WHERE id = ? AND state IN ('PENDING','RETRY_WAIT')",
            utc(now),
            CommunicatedSummary.current(jdbc, round, target).toJson(),
            messageId);
    if (changed != 1) {
      throw new IllegalStateException("message is not ready to send: " + messageId);
    }
    pinRecipient(messageId);
    return true;
  }

  /**
   * 첫 시도 때 연락처 버전, 동의 이벤트, 수신 번호 암호문 사본을 메시지에 고정한다(05 8.2절 5번). 다시 시도할 때는 바꾸지 않는다. 본문과 템플릿 버전은 발송기를
   * 연결할 때 함께 고정한다.
   */
  private void pinRecipient(long messageId) {
    jdbc.update(
        "UPDATE delivery_message m JOIN member_contact c ON c.member_id = m.member_id"
            + " SET m.recipient_version = c.version, m.consent_event_id = c.consent_event_id,"
            + " m.recipient_ciphertext = c.address_ciphertext, m.key_version = c.key_version"
            + " WHERE m.id = ? AND m.recipient_version IS NULL",
        messageId);
  }

  /** 제공자 호출 결과를 남긴다. IN_FLIGHT일 때만 바꾸고, 비용은 결과에 따라 확정·반환·유지한다. */
  public void recordOutcome(long messageId, SendOutcome outcome) {
    String state;
    String providerId = null;
    String error = null;
    if (outcome instanceof SendOutcome.Accepted a) {
      state = "ACCEPTED";
      providerId = a.providerMessageId();
    } else if (outcome instanceof SendOutcome.NotAccepted n) {
      state = "RETRY_WAIT";
      error = n.errorCode();
    } else if (outcome instanceof SendOutcome.Rejected r) {
      state = "FAILED";
      error = r.errorCode();
    } else {
      state = "UNKNOWN";
    }
    int changed =
        jdbc.update(
            "UPDATE delivery_message SET state = ?, provider_message_id = COALESCE(?, provider_message_id),"
                + " last_error_code = ?, finalized_at = IF(? = 'FAILED', ?, finalized_at),"
                + " version = version + 1 WHERE id = ? AND state = 'IN_FLIGHT'",
            state,
            providerId,
            error,
            state,
            utc(clock.instant()),
            messageId);
    if (changed != 1) {
      return;
    }
    switch (state) {
      case "ACCEPTED" -> budget.spend(messageId);
      case "RETRY_WAIT", "FAILED" -> budget.release(messageId);
      default -> {
        // UNKNOWN은 예약을 그대로 둔다
      }
    }
  }

  /** 제공자 조회로 전달이 확인됐다. ACCEPTED일 때만 DELIVERED로 바꾸고, 그 밖의 상태는 되돌리지 않는다. */
  public boolean markDelivered(long messageId) {
    return jdbc.update(
            "UPDATE delivery_message SET state = 'DELIVERED', finalized_at = ?, version = version + 1"
                + " WHERE id = ? AND state = 'ACCEPTED'",
            utc(clock.instant()),
            messageId)
        == 1;
  }

  /** 마지막 슬롯이 실패하면 남은 PENDING과 RETRY_WAIT를 HALTED로 바꾼다(05 8.2절 9번). */
  public int haltRemaining(int roundNo) {
    return jdbc.update(
        "UPDATE delivery_message SET state = 'HALTED', version = version + 1 WHERE round_no = ?"
            + " AND purpose = 'RESULT' AND state IN ('PENDING','RETRY_WAIT')",
        roundNo);
  }

  /** 보낼 차례의 메시지 ID. */
  public List<Long> sendable(int roundNo) {
    return jdbc.queryForList(
        "SELECT id FROM delivery_message WHERE round_no = ? AND purpose = 'RESULT'"
            + " AND state IN ('PENDING','RETRY_WAIT') ORDER BY id",
        Long.class,
        roundNo);
  }
}
