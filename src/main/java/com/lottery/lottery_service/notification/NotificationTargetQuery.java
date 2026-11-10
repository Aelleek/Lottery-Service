package com.lottery.lottery_service.notification;

import com.lottery.lottery_service.result.domain.PrizeRank;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 봉인한 회차의 기본 알림 대상을 기록의 시각으로 정한다(D-040, 05 5절 2번). 대상을 따로 저장하지 않는다. 마감 시각은 봉인 때 고정한 {@code
 * cutoff_close_at}이고, 등수는 현재 게시본과 현재 기록 버전의 판정이다.
 *
 * <ul>
 *   <li>회원 주체의 기록 중 {@code created_at < cutoff_close_at}이고 지워지지 않은 것이 후보다.
 *   <li>구매 번호: {@code purchased = 1}이고 {@code purchased_at < cutoff_close_at}. 1~5등.
 *   <li>추천 번호: {@code recommended_at}이 있다. 1~3등.
 * </ul>
 */
@Component
public class NotificationTargetQuery {

  private final JdbcTemplate jdbc;

  /** 조회기를 만든다. */
  public NotificationTargetQuery(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** 회원별 대상. 봉인 전이거나 게시본이 없으면 빈 목록이다. */
  public List<NotificationTarget> targets(int roundNo) {
    return query(roundNo, null);
  }

  /** 한 회원의 대상. 대상이 아니면 {@code null}. 메시지 하나를 보낼 때 회차 전체를 다시 읽지 않으려고 쓴다. */
  public NotificationTarget target(int roundNo, long memberId) {
    List<NotificationTarget> found = query(roundNo, memberId);
    return found.isEmpty() ? null : found.get(0);
  }

  private List<NotificationTarget> query(int roundNo, Long memberId) {
    List<Map<String, Object>> rows =
        jdbc.queryForList(
            "SELECT s.member_id, l.id, e.rank_no,"
                + " (l.purchased = 1 AND l.purchased_at < r.cutoff_close_at AND e.rank_no <= 5)"
                + " AS by_purchase FROM lotto_record l"
                + " JOIN actor_subject s ON s.id = l.owner_subject_id AND s.kind = 'MEMBER'"
                + " AND s.state = 'ACTIVE'"
                + " JOIN lotto_round r ON r.round_no = l.round_no"
                + " JOIN winning_evaluation e ON e.record_id = l.id"
                + " AND e.result_id = r.published_result_id AND e.record_version = l.version"
                + " WHERE l.round_no = ? AND r.cutoff_state = 'SEALED' AND l.deleted_at IS NULL"
                + " AND l.created_at < r.cutoff_close_at AND e.rank_no IS NOT NULL"
                + " AND ((l.purchased = 1 AND l.purchased_at < r.cutoff_close_at AND e.rank_no <= 5)"
                + " OR (l.recommended_at IS NOT NULL AND e.rank_no <= 3))"
                + (memberId == null ? "" : " AND s.member_id = ?")
                + " ORDER BY s.member_id, l.id",
            memberId == null ? new Object[] {roundNo} : new Object[] {roundNo, memberId});
    Map<Long, List<NotificationTarget.Item>> byMember = new LinkedHashMap<>();
    for (Map<String, Object> r : rows) {
      long member = ((Number) r.get("member_id")).longValue();
      int rank = ((Number) r.get("rank_no")).intValue();
      byMember
          .computeIfAbsent(member, k -> new ArrayList<>())
          .add(
              new NotificationTarget.Item(
                  ((Number) r.get("id")).longValue(),
                  PrizeRank.values()[rank - 1],
                  ((Number) r.get("by_purchase")).intValue() == 1));
    }
    List<NotificationTarget> targets = new ArrayList<>();
    byMember.forEach((member, items) -> targets.add(new NotificationTarget(member, items)));
    return targets;
  }
}
