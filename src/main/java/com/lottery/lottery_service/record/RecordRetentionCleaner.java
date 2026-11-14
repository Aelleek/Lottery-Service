package com.lottery.lottery_service.record;

import static com.lottery.lottery_service.common.db.DbValues.utc;

import java.time.Clock;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 보관 기간이 지난 기록을 정리한다(02 8절, 04 6.1절). 지우기 전에 이 기록을 가리키는 행을 순서대로 처리한다: 추천 항목 → 가져오기 항목 → 판정 → 기록.
 * 호출한 쪽 트랜잭션 안에서 묶음 하나를 처리한다.
 *
 * <p>정리 대상(추첨일+30일이 지난 회차):
 *
 * <ul>
 *   <li>회원: 구매하지 않았고 최신 판정이 낙첨인 추천 기록. 미판정, 정정 보호 중, 당첨, 구매 기록은 남긴다.
 *   <li>비회원: 그 회차의 기록 전부. 미판정이면 남긴다.
 * </ul>
 *
 * <p>회차가 정리 보류이거나, 알림 계획이 끝나지 않았거나, 회차 집계가 VERIFIED가 아니면 정리하지 않는다. 사용자가 지워 숨긴 기록은 {@link
 * #deleteHiddenRecords}가 따로 지운다.
 */
@Component
public class RecordRetentionCleaner {

  /** 추첨일 뒤 보관 기간. */
  public static final Duration RETENTION = Duration.ofDays(30);

  private final JdbcTemplate jdbc;
  private final Clock clock;

  /** 정리기를 만든다. */
  public RecordRetentionCleaner(JdbcTemplate jdbc, Clock clock) {
    this.jdbc = jdbc;
    this.clock = clock;
  }

  /**
   * 한 회차에서 정리할 기록을 묶음 하나만큼 지운다.
   *
   * @return 지운 기록 수
   */
  public int cleanupRound(int roundNo, int limit) {
    Boolean ready =
        jdbc.queryForObject(
            "SELECT r.published_result_id IS NOT NULL AND r.cleanup_held = FALSE"
                + " AND r.notification_plan_closed_at IS NOT NULL AND r.draw_at <= ?"
                + " AND EXISTS (SELECT 1 FROM usage_aggregate a WHERE a.period_type = 'ROUND'"
                + " AND a.period_key = CAST(r.round_no AS CHAR) AND a.state = 'VERIFIED')"
                + " FROM lotto_round r WHERE r.round_no = ?",
            Boolean.class,
            utc(clock.instant().minus(RETENTION)),
            roundNo);
    if (!Boolean.TRUE.equals(ready)) {
      return 0;
    }
    List<Long> ids =
        jdbc.queryForList(
            "SELECT l.id FROM lotto_record l JOIN actor_subject s ON s.id = l.owner_subject_id"
                + " JOIN lotto_round r ON r.round_no = l.round_no"
                + " JOIN winning_evaluation e ON e.record_id = l.id"
                + " AND e.result_id = r.published_result_id AND e.record_version = l.version"
                + " WHERE l.round_no = ? AND (l.correction_protected_until IS NULL"
                + " OR l.correction_protected_until <= ?)"
                + " AND (s.kind = 'GUEST' OR (l.purchased = FALSE AND e.rank_no IS NULL))"
                + " ORDER BY l.id LIMIT ? FOR UPDATE",
            Long.class,
            roundNo,
            utc(clock.instant()),
            limit);
    return delete(ids);
  }

  /**
   * 사용자가 지워 숨긴 기록({@code deleted_at})을 묶음 하나만큼 실제로 지운다(04 5절). 그 회차의 알림 계획이 끝났고, 그 기록을 결과로 가리키는 멱등
   * 기록이 만료됐고, 아직 남은 원본에서 그 기록으로 가져온 항목이 없을 때만 지운다. 보관 기간은 기다리지 않는다.
   *
   * @return 지운 기록 수
   */
  public int deleteHiddenRecords(int limit) {
    List<Long> ids =
        jdbc.queryForList(
            "SELECT l.id FROM lotto_record l JOIN lotto_round r ON r.round_no = l.round_no"
                + " WHERE l.deleted_at IS NOT NULL AND r.notification_plan_closed_at IS NOT NULL"
                + " AND NOT EXISTS (SELECT 1 FROM command_operation c"
                + " WHERE c.resource_public_id = l.public_id AND c.marker_expires_at > ?)"
                + " AND NOT EXISTS (SELECT 1 FROM history_import_item h"
                + " JOIN lotto_record src ON src.id = h.source_record_id"
                + " WHERE h.target_record_id = l.id)"
                + " ORDER BY l.id LIMIT ? FOR UPDATE",
            Long.class,
            utc(clock.instant()),
            limit);
    return delete(ids);
  }

  private int delete(List<Long> ids) {
    if (ids.isEmpty()) {
      return 0;
    }
    String in = String.join(",", Collections.nCopies(ids.size(), "?"));
    Object[] args = ids.toArray();
    jdbc.update("DELETE FROM recommendation_item WHERE record_id IN (" + in + ")", args);
    jdbc.update("DELETE FROM history_import_item WHERE source_record_id IN (" + in + ")", args);
    jdbc.update(
        "UPDATE history_import_item SET target_record_id = NULL WHERE target_record_id IN ("
            + in
            + ")",
        args);
    jdbc.update("DELETE FROM winning_evaluation WHERE record_id IN (" + in + ")", args);
    return jdbc.update("DELETE FROM lotto_record WHERE id IN (" + in + ")", args);
  }
}
