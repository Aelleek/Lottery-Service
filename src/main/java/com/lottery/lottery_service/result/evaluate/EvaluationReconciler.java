package com.lottery.lottery_service.result.evaluate;

import static com.lottery.lottery_service.common.db.DbValues.utc;

import com.lottery.lottery_service.common.job.JobStore;
import com.lottery.lottery_service.record.PurchaseStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 판정이 빠진 기록을 찾아 판정 job을 등록한다(07 1절 미평가 대조, 05 7절 4번). 결과가 게시된 최근 35일 회차에서, 현재 게시본과 현재 기록 버전의 판정이 없는
 * 기록이 대상이다. 같은 기록·버전의 job은 한 번만 등록된다.
 */
@Component
public class EvaluationReconciler {

  /** 대조하는 기간. */
  public static final Duration WINDOW = Duration.ofDays(35);

  private final JdbcTemplate jdbc;
  private final JobStore jobs;
  private final Clock clock;

  /** 대조기를 만든다. */
  public EvaluationReconciler(JdbcTemplate jdbc, JobStore jobs, Clock clock) {
    this.jdbc = jdbc;
    this.jobs = jobs;
    this.clock = clock;
  }

  /**
   * 빠진 판정을 찾아 job으로 등록한다. 호출한 쪽 트랜잭션이 있으면 거기에 참여한다.
   *
   * @param limit 한 번에 볼 기록 수
   * @return 새로 등록한 job 수
   */
  public int enqueueMissing(int limit) {
    Instant now = clock.instant();
    List<Map<String, Object>> missing =
        jdbc.queryForList(
            "SELECT l.id, l.version FROM lotto_record l JOIN lotto_round r ON r.round_no = l.round_no"
                + " WHERE r.published_result_id IS NOT NULL AND r.draw_at >= ? AND l.deleted_at IS NULL"
                + " AND NOT EXISTS (SELECT 1 FROM winning_evaluation e WHERE e.record_id = l.id"
                + " AND e.result_id = r.published_result_id AND e.record_version = l.version)"
                + " ORDER BY l.id LIMIT ?",
            utc(now.minus(WINDOW)),
            limit);
    int created = 0;
    for (Map<String, Object> row : missing) {
      long id = ((Number) row.get("id")).longValue();
      long version = ((Number) row.get("version")).longValue();
      if (jobs.enqueue(
          PurchaseStore.EVALUATE_RECORD_JOB,
          "record:" + id + ":v" + version,
          "{\"recordId\":" + id + "}",
          now)) {
        created++;
      }
    }
    return created;
  }
}
