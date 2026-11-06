package com.lottery.lottery_service.record;

import static com.lottery.lottery_service.common.db.DbValues.utc;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 매일 04:00 보관 정리의 나머지 대상(02 8절, 04 6.3절). 호출한 쪽 트랜잭션 안에서 부른다.
 *
 * <ul>
 *   <li>만료·폐기 후 7일이 지난 비회원 자격
 *   <li>기록·추천 이력·귀속·유효 자격이 하나도 없는 비회원 주체(명령 기록을 지우고 사용량 사건의 주체를 비운 뒤)
 *   <li>성공·취소 후 30일이 지난 job. 대기·실행 중과 DEAD는 남긴다
 *   <li>기록 정리가 끝난 회차의 추천 이력과 요청(판매 마감 전에는 지우지 않는다)
 * </ul>
 */
@Component
public class HousekeepingCleaner {

  /** 비회원 자격의 만료·폐기 뒤 보관 기간. */
  public static final Duration CREDENTIAL_RETENTION = Duration.ofDays(7);

  /** 끝난 job 보관 기간. */
  public static final Duration JOB_RETENTION = Duration.ofDays(30);

  private final JdbcTemplate jdbc;
  private final Clock clock;

  /** 정리기를 만든다. */
  public HousekeepingCleaner(JdbcTemplate jdbc, Clock clock) {
    this.jdbc = jdbc;
    this.clock = clock;
  }

  /** 만료·폐기 후 7일이 지난 비회원 자격을 지운다. */
  public int deleteExpiredGuestCredentials() {
    Object cutoff = utc(clock.instant().minus(CREDENTIAL_RETENTION));
    return jdbc.update(
        "DELETE FROM guest_credential WHERE expires_at <= ? OR revoked_at <= ?", cutoff, cutoff);
  }

  /** 남은 자료가 없는 비회원 주체를 지운다. */
  public int deleteEmptyGuestSubjects(int limit) {
    List<Long> ids =
        jdbc.queryForList(
            "SELECT s.id FROM actor_subject s WHERE s.kind = 'GUEST'"
                + " AND NOT EXISTS (SELECT 1 FROM lotto_record l WHERE l.owner_subject_id = s.id)"
                + " AND NOT EXISTS (SELECT 1 FROM recommendation_seen r WHERE r.subject_id = s.id)"
                + " AND NOT EXISTS (SELECT 1 FROM recommendation_request q WHERE q.subject_id = s.id)"
                + " AND NOT EXISTS (SELECT 1 FROM subject_claim c WHERE c.guest_subject_id = s.id)"
                + " AND NOT EXISTS (SELECT 1 FROM guest_credential g WHERE g.subject_id = s.id)"
                + " ORDER BY s.id LIMIT ? FOR UPDATE",
            Long.class,
            limit);
    if (ids.isEmpty()) {
      return 0;
    }
    String in = String.join(",", Collections.nCopies(ids.size(), "?"));
    Object[] args = ids.toArray();
    jdbc.update("DELETE FROM command_operation WHERE subject_id IN (" + in + ")", args);
    jdbc.update("UPDATE usage_event SET subject_id = NULL WHERE subject_id IN (" + in + ")", args);
    return jdbc.update("DELETE FROM actor_subject WHERE id IN (" + in + ")", args);
  }

  /** 성공·취소 후 30일이 지난 job을 지운다. */
  public int deleteFinishedJobs() {
    return jdbc.update(
        "DELETE FROM job WHERE state IN ('SUCCEEDED','CANCELED') AND finished_at <= ?",
        utc(clock.instant().minus(JOB_RETENTION)));
  }

  /**
   * 기록이 하나도 남지 않은 주체·회차의 추천 이력과 요청을 지운다. 판매 마감 전 회차는 건드리지 않는다(D-025).
   *
   * @return 지운 추천 요청 수
   */
  public int deleteOrphanRoundHistory(int roundNo) {
    Instant now = clock.instant();
    Boolean closed =
        jdbc.queryForObject(
            "SELECT sales_close_at <= ? FROM lotto_round WHERE round_no = ?",
            Boolean.class,
            utc(now),
            roundNo);
    if (!Boolean.TRUE.equals(closed)) {
      return 0;
    }
    String noRecords =
        " AND NOT EXISTS (SELECT 1 FROM lotto_record l WHERE l.owner_subject_id = %s.subject_id"
            + " AND l.round_no = %s.round_no)";
    jdbc.update(
        "DELETE s FROM recommendation_seen s WHERE s.round_no = ?" + noRecords.formatted("s", "s"),
        roundNo);
    jdbc.update(
        "DELETE i FROM recommendation_item i JOIN recommendation_request q ON q.id = i.request_id"
            + " WHERE q.round_no = ?"
            + noRecords.formatted("q", "q"),
        roundNo);
    return jdbc.update(
        "DELETE q FROM recommendation_request q WHERE q.round_no = ?"
            + noRecords.formatted("q", "q")
            + " AND NOT EXISTS (SELECT 1 FROM recommendation_seen s WHERE s.first_request_id = q.id)",
        roundNo);
  }
}
