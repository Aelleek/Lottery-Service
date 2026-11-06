package com.lottery.lottery_service.notification;

import static com.lottery.lottery_service.common.db.DbValues.instant;
import static com.lottery.lottery_service.common.db.DbValues.utc;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 회차의 알림 계획 완료 시각을 세운다(05 8.7절, C-18). 이 시각은 기록 정리의 전제이고, 한 번 세우면 되돌리지 않는다. 호출한 쪽 트랜잭션 안에서 부른다.
 *
 * <ol>
 *   <li>마지막 자동 슬롯(추첨 다음 날 12:00)이 지났다.
 *   <li>기본 알림의 수동 다시 보내기 기한(현재 게시본 게시 후 7일)이 지났다.
 *   <li>결과 알림 메시지에 PENDING, IN_FLIGHT, RETRY_WAIT, UNKNOWN이 없다.
 *   <li>알림 후보 기록(마감 전 회원 기록)의 판정이 현재 게시본과 현재 기록 버전으로 끝났다.
 * </ol>
 */
@Component
public class NotificationPlanCloser {

  /** 기본 알림 수동 다시 보내기 기한. */
  public static final Duration RESEND_WINDOW = Duration.ofDays(7);

  private final JdbcTemplate jdbc;
  private final Clock clock;

  /** 정리기를 만든다. */
  public NotificationPlanCloser(JdbcTemplate jdbc, Clock clock) {
    this.jdbc = jdbc;
    this.clock = clock;
  }

  /**
   * 조건을 모두 채우면 완료 시각을 세운다.
   *
   * @return 이번에 세웠으면 {@code true}. 이미 세웠거나 조건이 모자라면 {@code false}
   */
  public boolean closeIfDone(int roundNo) {
    Instant now = clock.instant();
    List<Map<String, Object>> rounds =
        jdbc.queryForList(
            "SELECT r.draw_at, r.cutoff_state, r.notification_plan_closed_at, d.published_at"
                + " FROM lotto_round r JOIN draw_result d ON d.id = r.published_result_id"
                + " WHERE r.round_no = ? FOR UPDATE",
            roundNo);
    if (rounds.isEmpty()) {
      return false;
    }
    Map<String, Object> round = rounds.get(0);
    if (round.get("notification_plan_closed_at") != null
        || !"SEALED".equals(round.get("cutoff_state"))) {
      return false;
    }
    List<ResultSlot> slots =
        ResultSlotSchedule.slots(instant((LocalDateTime) round.get("draw_at")));
    Instant lastSlot = slots.get(slots.size() - 1).at();
    Instant resendDeadline = instant((LocalDateTime) round.get("published_at")).plus(RESEND_WINDOW);
    if (now.isBefore(lastSlot) || now.isBefore(resendDeadline)) {
      return false;
    }
    Long open =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM delivery_message WHERE round_no = ? AND purpose = 'RESULT'"
                + " AND state IN ('PENDING','IN_FLIGHT','RETRY_WAIT','UNKNOWN')",
            Long.class,
            roundNo);
    Long unevaluated =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM lotto_record l JOIN lotto_round r ON r.round_no = l.round_no"
                + " JOIN actor_subject s ON s.id = l.owner_subject_id AND s.kind = 'MEMBER'"
                + " WHERE l.round_no = ? AND l.deleted_at IS NULL AND l.created_at < r.cutoff_close_at"
                + " AND NOT EXISTS (SELECT 1 FROM winning_evaluation e WHERE e.record_id = l.id"
                + " AND e.result_id = r.published_result_id AND e.record_version = l.version)",
            Long.class,
            roundNo);
    if (open != 0 || unevaluated != 0) {
      return false;
    }
    jdbc.update(
        "UPDATE lotto_round SET notification_plan_closed_at = ? WHERE round_no = ?"
            + " AND notification_plan_closed_at IS NULL",
        utc(now),
        roundNo);
    return true;
  }
}
