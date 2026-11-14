package com.lottery.lottery_service.record;

import static com.lottery.lottery_service.common.db.DbValues.utc;

import com.lottery.lottery_service.common.job.JobStore;
import com.lottery.lottery_service.notification.NotificationPlanCloser;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 매일 정리 때의 회차 작업(07 1절 보관 정리). 알림 계획 완료 조건을 확인하고, 보관 기간이 지난 회차의 기록을 묶음마다 짧은 트랜잭션으로 지우고, 보호 조건 때문에
 * 보관 기간 뒤 7일이 넘도록 못 지운 기록을 운영자에게 알린다(07 2.1절 정리 지연).
 */
@Component
public class RoundMaintenance {

  /** 보관 기간이 지난 뒤 이만큼 더 남아 있으면 정리 지연으로 알린다. */
  public static final Duration DELAY_ALERT_AFTER = Duration.ofDays(7);

  /** 운영자 알림 종류. */
  public static final String ALERT_KIND = "CLEANUP_DELAYED";

  private static final String CANDIDATES =
      " FROM lotto_record l JOIN actor_subject s ON s.id = l.owner_subject_id"
          + " JOIN lotto_round r ON r.round_no = l.round_no"
          + " LEFT JOIN winning_evaluation e ON e.record_id = l.id"
          + " AND e.result_id = r.published_result_id AND e.record_version = l.version"
          + " WHERE l.round_no = ? AND (s.kind = 'GUEST' OR (l.purchased = FALSE"
          + " AND e.rank_no IS NULL))";

  private final JdbcTemplate jdbc;
  private final NotificationPlanCloser planCloser;

  /** 숨긴 기록 정리가 하루에 도는 최대 묶음 수. */
  static final int MAX_HIDDEN_BATCHES = 1_000;

  private final RecordRetentionCleaner cleaner;
  private final HousekeepingCleaner housekeeping;
  private final JobStore jobs;
  private final TransactionTemplate tx;
  private final Clock clock;

  /** 작업기를 만든다. */
  public RoundMaintenance(
      JdbcTemplate jdbc,
      NotificationPlanCloser planCloser,
      RecordRetentionCleaner cleaner,
      HousekeepingCleaner housekeeping,
      JobStore jobs,
      PlatformTransactionManager transactionManager,
      Clock clock) {
    this.jdbc = jdbc;
    this.planCloser = planCloser;
    this.cleaner = cleaner;
    this.housekeeping = housekeeping;
    this.jobs = jobs;
    this.tx = new TransactionTemplate(transactionManager);
    this.clock = clock;
  }

  /**
   * 결과가 게시됐고 알림 계획이 아직 열린 회차마다 완료 조건을 확인한다.
   *
   * @return 이번에 완료한 회차 수
   */
  public int closeNotificationPlans() {
    int closed = 0;
    for (int round :
        jdbc.queryForList(
            "SELECT round_no FROM lotto_round WHERE published_result_id IS NOT NULL"
                + " AND notification_plan_closed_at IS NULL ORDER BY round_no",
            Integer.class)) {
      closed += Boolean.TRUE.equals(tx.execute(status -> planCloser.closeIfDone(round))) ? 1 : 0;
    }
    return closed;
  }

  /**
   * 보관 기간이 지난 회차의 기록을 지운다. 정리 조건은 {@link RecordRetentionCleaner}가 확인한다. 이어서 기록이 남지 않은 주체·회차의 추천 이력과
   * 요청을 지운다(04 5절 순서: 기록, 추천 이력, 추천 요청). 그래야 빈 비회원 주체도 지울 수 있다.
   *
   * @param batch 한 트랜잭션에서 지울 기록 수
   * @return 지운 기록 수
   */
  public int cleanupRecords(int batch) {
    int deleted = 0;
    for (int i = 0; i < MAX_HIDDEN_BATCHES; i++) {
      Integer n = tx.execute(status -> cleaner.deleteHiddenRecords(batch));
      deleted += n == null ? 0 : n;
      if (n == null || n < batch) {
        break;
      }
    }
    for (int round :
        jdbc.queryForList(
            "SELECT r.round_no FROM lotto_round r WHERE r.draw_at <= ? AND EXISTS (SELECT 1"
                + " FROM lotto_record l WHERE l.round_no = r.round_no) ORDER BY r.round_no",
            Integer.class,
            utc(clock.instant().minus(RecordRetentionCleaner.RETENTION)))) {
      while (true) {
        Integer n = tx.execute(status -> cleaner.cleanupRound(round, batch));
        deleted += n == null ? 0 : n;
        if (n == null || n < batch) {
          break;
        }
      }
    }
    for (int round :
        jdbc.queryForList(
            "SELECT r.round_no FROM lotto_round r WHERE r.draw_at <= ? AND EXISTS (SELECT 1"
                + " FROM recommendation_request q WHERE q.round_no = r.round_no) ORDER BY r.round_no",
            Integer.class,
            utc(clock.instant().minus(RecordRetentionCleaner.RETENTION)))) {
      tx.execute(status -> housekeeping.deleteOrphanRoundHistory(round));
    }
    return deleted;
  }

  /**
   * 보관 기간 뒤 7일이 지나도 남은 정리 대상을 회차·이유마다 한 번 운영자에게 알린다. 정리 뒤에 부른다. 이유는 회차 단위(정리 보류, 결과 미게시, 알림 계획 미완료,
   * 집계 미확정)를 먼저 보고, 없으면 기록 단위(미판정, 아직 지우지 못한 숨긴 기록, 정정 보호)를 본다.
   *
   * @return 이번에 등록한 알림 수
   */
  public int alertDelayedCleanup() {
    Instant now = clock.instant();
    int alerts = 0;
    List<Map<String, Object>> rounds =
        jdbc.queryForList(
            "SELECT r.round_no, r.cleanup_held, r.published_result_id IS NULL AS unpublished,"
                + " r.notification_plan_closed_at IS NULL AS plan_open,"
                + " NOT EXISTS (SELECT 1 FROM usage_aggregate a WHERE a.period_type = 'ROUND'"
                + " AND a.period_key = CAST(r.round_no AS CHAR) AND a.state = 'VERIFIED')"
                + " AS unverified FROM lotto_round r WHERE r.draw_at <= ?"
                + " AND EXISTS (SELECT 1 FROM lotto_record l WHERE l.round_no = r.round_no)"
                + " ORDER BY r.round_no",
            utc(now.minus(RecordRetentionCleaner.RETENTION).minus(DELAY_ALERT_AFTER)));
    for (Map<String, Object> row : rounds) {
      int round = ((Number) row.get("round_no")).intValue();
      String roundReason = roundReason(row);
      if (roundReason != null) {
        long count = jdbc.queryForObject("SELECT COUNT(*)" + CANDIDATES, Long.class, round);
        alerts += alert(round, roundReason, count) ? 1 : 0;
        continue;
      }
      Map<String, Object> counts =
          jdbc.queryForMap(
              "SELECT COALESCE(SUM(e.record_id IS NULL AND l.deleted_at IS NULL), 0) AS unevaluated,"
                  + " COALESCE(SUM(l.deleted_at IS NOT NULL), 0) AS hidden,"
                  + " COALESCE(SUM(e.record_id IS NOT NULL AND l.correction_protected_until > ?), 0)"
                  + " AS protected"
                  + CANDIDATES,
              utc(now),
              round);
      alerts +=
          alert(round, "UNEVALUATED", ((Number) counts.get("unevaluated")).longValue()) ? 1 : 0;
      alerts +=
          alert(round, "HIDDEN_NOT_DELETED", ((Number) counts.get("hidden")).longValue()) ? 1 : 0;
      alerts +=
          alert(round, "CORRECTION_PROTECTED", ((Number) counts.get("protected")).longValue())
              ? 1
              : 0;
    }
    return alerts;
  }

  private static String roundReason(Map<String, Object> row) {
    if (truthy(row.get("cleanup_held"))) {
      return "CLEANUP_HELD";
    }
    if (truthy(row.get("unpublished"))) {
      return "RESULT_NOT_PUBLISHED";
    }
    if (truthy(row.get("plan_open"))) {
      return "NOTIFICATION_PLAN_OPEN";
    }
    if (truthy(row.get("unverified"))) {
      return "AGGREGATE_NOT_VERIFIED";
    }
    return null;
  }

  private boolean alert(int round, String reason, long count) {
    if (count == 0) {
      return false;
    }
    return jobs.enqueue(
        JobStore.OPERATOR_ALERT,
        "cleanup-delayed:" + round + ":" + reason,
        "{\"kind\":\""
            + ALERT_KIND
            + "\",\"roundNo\":"
            + round
            + ",\"reason\":\""
            + reason
            + "\",\"count\":"
            + count
            + "}",
        clock.instant());
  }

  private static boolean truthy(Object value) {
    return value instanceof Boolean b ? b : value != null && ((Number) value).intValue() != 0;
  }
}
