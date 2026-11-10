package com.lottery.lottery_service.record;

import static com.lottery.lottery_service.common.db.DbValues.utc;

import com.lottery.lottery_service.common.db.DbValues;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 회원이 아직 가져오지 않은 비회원 귀속 목록({@code GET /me/guest-claims}, 2026-11-09 Q20). 가져올 기록이 남은 귀속만 준다. 가져올
 * 기록은 지우지 않았고 보관 기한이 지나지 않은 비회원 기록이다. 기한은 기록의 {@code expires_at}, 없으면 비회원 보관 기간(추첨일+30일, 02 8절)이다.
 */
@Component
public class GuestClaimQuery {

  /** 한 번에 주는 귀속 수 상한. */
  public static final int MAX_ITEMS = 50;

  private final JdbcTemplate jdbc;
  private final Clock clock;

  /** 조회기를 만든다. */
  public GuestClaimQuery(JdbcTemplate jdbc, Clock clock) {
    this.jdbc = jdbc;
    this.clock = clock;
  }

  /** 회원 주체에게 귀속됐고 아직 가져오지 않은 귀속을 귀속 순서로 준다. */
  public List<GuestClaimView> pending(long memberSubjectId) {
    Instant now = clock.instant();
    return jdbc.query(
        "SELECT c.public_id, COUNT(s.id) AS record_count,"
            + " MIN(COALESCE(s.expires_at, r.draw_at + INTERVAL 30 DAY)) AS expires_at"
            + " FROM subject_claim c JOIN lotto_record s ON s.owner_subject_id = c.guest_subject_id"
            + " AND s.deleted_at IS NULL"
            + " JOIN lotto_round r ON r.round_no = s.round_no"
            + " AND COALESCE(s.expires_at, r.draw_at + INTERVAL 30 DAY) > ?"
            + " WHERE c.member_subject_id = ? AND c.imported_at IS NULL"
            + " GROUP BY c.id, c.public_id, c.claimed_at ORDER BY c.claimed_at, c.id LIMIT ?",
        (rs, i) ->
            new GuestClaimView(
                DbValues.uuid(rs.getBytes("public_id")),
                rs.getLong("record_count"),
                rs.getObject("expires_at", LocalDateTime.class) == null
                    ? null
                    : DbValues.instant(rs.getObject("expires_at", LocalDateTime.class))),
        utc(now),
        memberSubjectId,
        MAX_ITEMS);
  }

  /**
   * 가져오지 않은 귀속 하나.
   *
   * @param claimId 귀속 외부 ID(가져오기 API의 {@code claimId})
   * @param recordCount 가져올 수 있는 기록 수
   * @param expiresAt 가장 먼저 보관 기한이 끝나는 기록의 기한. 이 시각 뒤로 가져올 수 있는 기록이 줄어들 수 있다
   */
  public record GuestClaimView(UUID claimId, long recordCount, Instant expiresAt) {}
}
