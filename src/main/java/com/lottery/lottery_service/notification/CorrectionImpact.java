package com.lottery.lottery_service.notification;

import com.lottery.lottery_service.common.job.JobStore;
import com.lottery.lottery_service.result.evaluate.ResultEvaluatedListener;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 정정 게시본의 판정이 끝나면 영향을 운영자에게 알린다(05 8.5절 5번, 07 2.1절 공식 결과 정정 반영). 접수·전달된 결과 메시지의 안내 요약과 새 판정을 비교한
 * "등수나 금액이 바뀐 회원 수"와, 대상인데 메시지가 없는 "새로 대상이 된 회원 수"를 담는다. 번호와 회원 ID는 넣지 않는다. 정정 알림은 회원에게 보내지
 * 않는다(D-042).
 */
@Component
public class CorrectionImpact implements ResultEvaluatedListener {

  /** 운영자 알림 종류. */
  public static final String ALERT_KIND = "RESULT_CORRECTED";

  private final JdbcTemplate jdbc;
  private final NotificationTargetQuery targets;
  private final JobStore jobs;
  private final Clock clock;

  /** 계산기를 만든다. */
  public CorrectionImpact(
      JdbcTemplate jdbc, NotificationTargetQuery targets, JobStore jobs, Clock clock) {
    this.jdbc = jdbc;
    this.targets = targets;
    this.jobs = jobs;
    this.clock = clock;
  }

  /** 직전 게시본이 있는(정정) 게시본이면 영향을 세어 알림 job을 한 번 등록한다. */
  @Override
  public void evaluated(long resultId) {
    List<Integer> rounds =
        jdbc.queryForList(
            "SELECT d.round_no FROM draw_result d WHERE d.id = ? AND EXISTS (SELECT 1"
                + " FROM draw_result o WHERE o.round_no = d.round_no AND o.state = 'SUPERSEDED'"
                + " AND o.published_at IS NOT NULL)",
            Integer.class,
            resultId);
    if (rounds.isEmpty()) {
      return;
    }
    Impact impact = measure(rounds.get(0));
    jobs.enqueue(
        JobStore.OPERATOR_ALERT,
        "result-corrected:" + resultId,
        "{\"kind\":\""
            + ALERT_KIND
            + "\",\"roundNo\":"
            + rounds.get(0)
            + ",\"changed\":"
            + impact.changed()
            + ",\"newlyTargeted\":"
            + impact.newlyTargeted()
            + "}",
        clock.instant());
  }

  /**
   * 회차의 정정 영향을 센다.
   *
   * @param roundNo 회차
   */
  public Impact measure(int roundNo) {
    Map<Long, NotificationTarget> byMember =
        targets.targets(roundNo).stream()
            .collect(Collectors.toMap(NotificationTarget::memberId, Function.identity()));
    List<Map<String, Object>> told =
        jdbc.queryForList(
            "SELECT member_id, CAST(communicated_summary AS CHAR) AS summary FROM delivery_message"
                + " WHERE round_no = ? AND purpose = 'RESULT' AND state IN ('ACCEPTED','DELIVERED')"
                + " AND communicated_summary IS NOT NULL",
            roundNo);
    long changed = 0;
    for (Map<String, Object> row : told) {
      long member = ((Number) row.get("member_id")).longValue();
      CommunicatedSummary now = CommunicatedSummary.current(jdbc, roundNo, byMember.get(member));
      if (CommunicatedSummary.parse((String) row.get("summary")).differsFrom(now)) {
        changed++;
      }
    }
    List<Long> withMessage =
        jdbc.queryForList(
            "SELECT member_id FROM delivery_message WHERE round_no = ? AND purpose = 'RESULT'",
            Long.class,
            roundNo);
    long newlyTargeted = byMember.keySet().stream().filter(m -> !withMessage.contains(m)).count();
    return new Impact(changed, newlyTargeted);
  }

  /**
   * 정정 영향.
   *
   * @param changed 접수·전달된 안내와 등수나 금액이 달라진 회원 수
   * @param newlyTargeted 대상인데 결과 메시지가 없는 회원 수
   */
  public record Impact(long changed, long newlyTargeted) {}
}
