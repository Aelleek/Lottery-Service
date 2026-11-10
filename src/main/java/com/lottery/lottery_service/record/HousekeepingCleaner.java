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
 *   <li>최종 상태 뒤 30일이 지난 알림 본문과 수신자 사본
 *   <li>90일이 지났고 집계가 확정된 통계 원천 사건
 * </ul>
 */
@Component
public class HousekeepingCleaner {

  /** 비회원 자격의 만료·폐기 뒤 보관 기간. */
  public static final Duration CREDENTIAL_RETENTION = Duration.ofDays(7);

  /** 알림 본문과 수신자 사본의 최종 상태 뒤 보관 기간. */
  public static final Duration MESSAGE_COPY_RETENTION = Duration.ofDays(30);

  /** 인증 코드 요청의 대상 암호문을 남기는 기간(사용·만료 후). */
  public static final Duration CHALLENGE_TARGET_RETENTION = Duration.ofDays(30);

  /** 통계 원천 사건 보관 기간. */
  public static final Duration USAGE_EVENT_RETENTION = Duration.ofDays(90);

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

  /** 표시 기한(명령 토큰 만료 후 7일)이 지난 멱등 기록을 지운다(02 8절). 지운 뒤 같은 요청은 토큰 만료로 410을 받는다. */
  public int deleteExpiredCommandOperations(int limit) {
    return jdbc.update(
        "DELETE FROM command_operation WHERE marker_expires_at <= ? ORDER BY marker_expires_at"
            + " LIMIT ?",
        utc(clock.instant()),
        limit);
  }

  /** 보관 기한(기록 후 1년)이 지난 관리자 감사 기록을 지운다(02 8절, D-020). */
  public int deleteExpiredAuditEvents(int limit) {
    return jdbc.update(
        "DELETE FROM admin_audit_event WHERE expires_at <= ? ORDER BY expires_at LIMIT ?",
        utc(clock.instant()),
        limit);
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

  /**
   * 최종 상태가 된 뒤 30일이 지난 메시지의 본문과 수신자 사본을 비운다(02 8절). 접수 확인 중(UNKNOWN)과 아직 보낼 메시지는 건드리지 않는다. 메시지 행과
   * 상태·비용, 안내 요약은 남긴다(예산 대조와 정정 영향 계산에 쓴다). 최종 시각은 끝난 시각, 없으면 마지막 시도 시각, 그것도 없으면 유효기간 끝이다.
   *
   * @return 비운 메시지 수
   */
  public int clearMessageCopies(int limit) {
    return jdbc.update(
        "UPDATE delivery_message SET payload_ciphertext = NULL, recipient_ciphertext = NULL,"
            + " payload_hash = NULL, version = version + 1"
            + " WHERE state IN ('ACCEPTED','DELIVERED','FAILED','SUPPRESSED','EXPIRED','HALTED')"
            + " AND (payload_ciphertext IS NOT NULL OR recipient_ciphertext IS NOT NULL"
            + " OR payload_hash IS NOT NULL)"
            + " AND COALESCE(finalized_at, last_attempt_at, expires_at) <= ? ORDER BY id LIMIT ?",
        utc(clock.instant().minus(MESSAGE_COPY_RETENTION)),
        limit);
  }

  /**
   * 사용·만료 후 30일이 지난 인증 코드 요청의 대상 번호 암호문을 비운다(02 8절, 2026-11-09 Q21). 행(요청 ID, 상태, 시각, HMAC)은 같은 달
   * 예산 대조에 쓰는 메시지 행과 함께 남긴다. 기준 시각은 사용한 시각, 없으면 유효기간 끝이다.
   *
   * @return 비운 요청 수
   */
  public int clearChallengeTargets(int limit) {
    return jdbc.update(
        "UPDATE verification_challenge SET target_ciphertext = NULL"
            + " WHERE target_ciphertext IS NOT NULL AND COALESCE(consumed_at, expires_at) <= ?"
            + " ORDER BY id LIMIT ?",
        utc(clock.instant().minus(CHALLENGE_TARGET_RETENTION)),
        limit);
  }

  /**
   * 90일이 지났고 그날·그달·그 회차 집계가 모두 VERIFIED인 통계 원천 사건을 지운다(02 8절, D-011). 확정된 집계 값은 바뀌지 않는다.
   *
   * @return 지운 사건 수
   */
  public int deleteAggregatedUsageEvents(int limit) {
    return jdbc.update(
        "DELETE FROM usage_event WHERE occurred_at <= ?"
            + " AND EXISTS (SELECT 1 FROM usage_aggregate d WHERE d.period_type = 'DAY'"
            + " AND d.period_key = CAST(usage_event.business_date AS CHAR) AND d.state = 'VERIFIED')"
            + " AND EXISTS (SELECT 1 FROM usage_aggregate m WHERE m.period_type = 'MONTH'"
            + " AND m.period_key = DATE_FORMAT(usage_event.business_date, '%Y-%m')"
            + " AND m.state = 'VERIFIED')"
            + " AND (usage_event.round_no IS NULL OR EXISTS (SELECT 1 FROM usage_aggregate r"
            + " WHERE r.period_type = 'ROUND' AND r.period_key = CAST(usage_event.round_no AS CHAR)"
            + " AND r.state = 'VERIFIED')) LIMIT ?",
        utc(clock.instant().minus(USAGE_EVENT_RETENTION)), limit);
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
