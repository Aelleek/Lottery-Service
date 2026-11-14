package com.lottery.lottery_service.notification;

import static com.lottery.lottery_service.common.db.DbValues.instant;
import static com.lottery.lottery_service.common.db.DbValues.utc;

import com.lottery.lottery_service.common.job.JobStore;
import com.lottery.lottery_service.common.time.SeoulTime;
import com.lottery.lottery_service.notification.budget.MessageBudgetStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 접수 여부를 모르는 메시지(UNKNOWN)를 제공자 조회 결과로 정리한다(05 8.3절). 호출한 쪽 트랜잭션 안에서 부른다. 조회 자체는 트랜잭션 밖에서 한다.
 *
 * <ul>
 *   <li>접수·전달이 확인되면 ACCEPTED·DELIVERED와 비용 확정.
 *   <li>제공자가 실패로 처리했으면 FAILED와 예약 반환.
 *   <li>찾지 못했으면 그대로 둔다. 다만 UNKNOWN이 된 뒤 7일이 지나면 FAILED로 끝내고 비용은 쓴 것으로 보며 운영자에게 알린다.
 *   <li>이미 끝난 메시지에 늦게 온 조회 결과로는 다시 보내지 않고 상태를 뒤로 돌리지 않는다.
 * </ul>
 */
@Component
public class UnknownMessageReconciler {

  /** UNKNOWN 대조 기간. */
  public static final Duration RECONCILE_WINDOW = Duration.ofDays(7);

  /** 이만큼 넘게 UNKNOWN이면 장기화로 알린다(07 2.1절, C-16 초기값). */
  public static final Duration LONG_UNKNOWN = Duration.ofHours(24);

  private final JdbcTemplate jdbc;
  private final MessageBudgetStore budget;
  private final JobStore jobs;
  private final Clock clock;

  /** 대조기를 만든다. */
  public UnknownMessageReconciler(
      JdbcTemplate jdbc, MessageBudgetStore budget, JobStore jobs, Clock clock) {
    this.jdbc = jdbc;
    this.budget = budget;
    this.jobs = jobs;
    this.clock = clock;
  }

  /**
   * 조회 결과를 반영한다.
   *
   * @return 반영 뒤 상태
   */
  public String apply(long messageId, ProviderStatus status) {
    List<Map<String, Object>> rows =
        jdbc.queryForList(
            "SELECT state, last_attempt_at FROM delivery_message WHERE id = ? FOR UPDATE",
            messageId);
    if (rows.isEmpty()) {
      throw new IllegalArgumentException("unknown message: " + messageId);
    }
    String state = (String) rows.get(0).get("state");
    if (!"UNKNOWN".equals(state)) {
      if ("ACCEPTED".equals(state) && status instanceof ProviderStatus.Delivered) {
        set(messageId, "DELIVERED", null, true);
        return "DELIVERED";
      }
      return state;
    }
    Instant now = clock.instant();
    if (status instanceof ProviderStatus.Accepted a) {
      set(messageId, "ACCEPTED", a.providerMessageId(), false);
      budget.spend(messageId);
      return "ACCEPTED";
    }
    if (status instanceof ProviderStatus.Delivered d) {
      set(messageId, "DELIVERED", d.providerMessageId(), true);
      budget.spend(messageId);
      return "DELIVERED";
    }
    if (status instanceof ProviderStatus.Failed) {
      set(messageId, "FAILED", null, true);
      budget.release(messageId);
      return "FAILED";
    }
    Instant since = instant((LocalDateTime) rows.get(0).get("last_attempt_at"));
    if (since != null && !now.isBefore(since.plus(RECONCILE_WINDOW))) {
      set(messageId, "FAILED", null, true);
      jdbc.update(
          "UPDATE delivery_message SET last_error_code = 'RECONCILE_TIMEOUT' WHERE id = ?",
          messageId);
      budget.spend(messageId);
      return "FAILED";
    }
    return "UNKNOWN";
  }

  /**
   * 대조 기간이 지나 FAILED로 끝낸 메시지를 회차마다 묶어 운영자 알림 1건씩 등록한다(07 2.1절 "접수 확인 종결": 회차, 건수). 인증 메시지는 회차 없이 한
   * 건으로 묶는다. 주어진 메시지 중 종결 규칙으로 끝난 것만 센다.
   *
   * @return 등록한 알림 수
   */
  public int alertReconcileTimeouts(List<Long> messageIds) {
    if (messageIds.isEmpty()) {
      return 0;
    }
    String in = String.join(",", Collections.nCopies(messageIds.size(), "?"));
    List<Map<String, Object>> groups =
        jdbc.queryForList(
            "SELECT round_no, COUNT(*) AS n, MIN(id) AS first_id FROM delivery_message"
                + " WHERE state = 'FAILED' AND last_error_code = 'RECONCILE_TIMEOUT' AND id IN ("
                + in
                + ") GROUP BY round_no",
            messageIds.toArray());
    int alerts = 0;
    for (Map<String, Object> g : groups) {
      Object round = g.get("round_no");
      alerts +=
          jobs.enqueue(
                  JobStore.OPERATOR_ALERT,
                  "message-unresolved:"
                      + (round == null ? "verification" : round)
                      + ":"
                      + g.get("first_id"),
                  "{\"kind\":\"MESSAGE_UNRESOLVED\",\"roundNo\":"
                      + (round == null ? "null" : round)
                      + ",\"count\":"
                      + ((Number) g.get("n")).longValue()
                      + "}",
                  clock.instant())
              ? 1
              : 0;
    }
    return alerts;
  }

  /**
   * 마지막 발송 시도 뒤 24시간 넘게 UNKNOWN인 메시지가 있으면 서울 날짜마다 한 번 운영자 알림을 등록한다(건수와 가장 오래된 시도 시각).
   *
   * @return 이번에 등록했으면 {@code true}
   */
  public boolean alertLongUnresolved() {
    Instant now = clock.instant();
    Map<String, Object> row =
        jdbc.queryForMap(
            "SELECT COUNT(*) AS c, MIN(last_attempt_at) AS oldest FROM delivery_message"
                + " WHERE state = 'UNKNOWN' AND last_attempt_at <= ?",
            utc(now.minus(LONG_UNKNOWN)));
    long count = ((Number) row.get("c")).longValue();
    if (count == 0) {
      return false;
    }
    return jobs.enqueue(
        JobStore.OPERATOR_ALERT,
        "message-unknown-long:" + SeoulTime.dateOf(now),
        "{\"kind\":\"MESSAGE_UNKNOWN_LONG\",\"count\":"
            + count
            + ",\"oldest\":\""
            + instant((LocalDateTime) row.get("oldest"))
            + "\"}",
        now);
  }

  /**
   * 발송 슬롯 job을 회수할 때 그 회차의 IN_FLIGHT 메시지를 UNKNOWN으로 바꾼다. 다시 호출하지 않고 대조한다(05 8.3절, T27). 비용은 예약 그대로
   * 둔다.
   *
   * @return 바꾼 메시지 수
   */
  public int markInFlightUnknown(int roundNo) {
    return jdbc.update(
        "UPDATE delivery_message SET state = 'UNKNOWN', version = version + 1 WHERE round_no = ?"
            + " AND purpose = 'RESULT' AND state = 'IN_FLIGHT'",
        roundNo);
  }

  /**
   * lease 만료로 회수한 job이 결과 알림 슬롯이면 그 회차의 IN_FLIGHT 메시지를 UNKNOWN으로 바꾼다(05 8.3절). 다른 종류의 job이면 아무것도 하지
   * 않는다.
   *
   * @return 바꾼 메시지 수
   */
  public int markInFlightUnknownForSlotJob(long jobId) {
    List<Integer> rounds =
        jdbc.queryForList(
            "SELECT CAST(JSON_EXTRACT(payload, '$.roundNo') AS SIGNED) FROM job"
                + " WHERE id = ? AND job_type = ?",
            Integer.class,
            jobId,
            ResultSlotJobHandler.JOB);
    return rounds.isEmpty() || rounds.get(0) == null ? 0 : markInFlightUnknown(rounds.get(0));
  }

  private void set(long messageId, String state, String providerId, boolean finalized) {
    jdbc.update(
        "UPDATE delivery_message SET state = ?, provider_message_id = COALESCE(?, provider_message_id),"
            + " finalized_at = IF(?, ?, finalized_at), version = version + 1 WHERE id = ?",
        state,
        providerId,
        finalized,
        utc(clock.instant()),
        messageId);
  }
}
