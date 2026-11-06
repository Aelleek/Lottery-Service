package com.lottery.lottery_service.account;

import static com.lottery.lottery_service.common.db.DbValues.bytes;
import static com.lottery.lottery_service.common.db.DbValues.utc;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 로그인할 때 비회원 주체를 회원 주체에 귀속한다(05 4절 2번). 두 주체를 ID 오름차순으로 잠그고, 비회원 주체 하나는 한 회원에게만 귀속한다. 기록을 옮기는 가져오기는
 * 하지 않는다.
 */
@Component
public class SubjectClaimStore {

  private final JdbcTemplate jdbc;
  private final TransactionTemplate tx;
  private final Clock clock;

  /** 저장소를 만든다. */
  public SubjectClaimStore(
      JdbcTemplate jdbc, PlatformTransactionManager transactionManager, Clock clock) {
    this.jdbc = jdbc;
    this.tx = new TransactionTemplate(transactionManager);
    this.clock = clock;
  }

  /**
   * 비회원 주체를 회원 주체에 귀속한다. 비회원 주체는 CLAIMED가 되고 귀속 버전이 오른다.
   *
   * @return 귀속 결과. 실패해도 예외를 던지지 않아 로그인을 막지 않는다
   */
  public ClaimResult claim(long guestSubjectId, long memberSubjectId) {
    if (guestSubjectId == memberSubjectId) {
      return ClaimResult.UNAVAILABLE;
    }
    return tx.execute(status -> claimInTransaction(guestSubjectId, memberSubjectId));
  }

  private ClaimResult claimInTransaction(long guestSubjectId, long memberSubjectId) {
    List<Map<String, Object>> subjects =
        jdbc.queryForList(
            "SELECT id, kind, state FROM actor_subject WHERE id IN (?, ?) ORDER BY id FOR UPDATE",
            Math.min(guestSubjectId, memberSubjectId),
            Math.max(guestSubjectId, memberSubjectId));
    Map<String, Object> guest = find(subjects, guestSubjectId);
    Map<String, Object> member = find(subjects, memberSubjectId);
    if (guest == null
        || member == null
        || !"GUEST".equals(guest.get("kind"))
        || !"MEMBER".equals(member.get("kind"))
        || !"ACTIVE".equals(member.get("state"))) {
      return ClaimResult.UNAVAILABLE;
    }
    if ("CLAIMED".equals(guest.get("state"))) {
      Long owner =
          jdbc.queryForObject(
              "SELECT member_subject_id FROM subject_claim WHERE guest_subject_id = ?",
              Long.class,
              guestSubjectId);
      return owner != null && owner == memberSubjectId
          ? ClaimResult.ALREADY_CLAIMED
          : ClaimResult.UNAVAILABLE;
    }
    jdbc.update(
        "INSERT INTO subject_claim (public_id, guest_subject_id, member_subject_id, claimed_at)"
            + " VALUES (?, ?, ?, ?)",
        bytes(UUID.randomUUID()),
        guestSubjectId,
        memberSubjectId,
        utc(clock.instant()));
    jdbc.update(
        "UPDATE actor_subject SET state = 'CLAIMED', claim_version = claim_version + 1"
            + " WHERE id = ?",
        guestSubjectId);
    jdbc.update(
        "UPDATE actor_subject SET claim_version = claim_version + 1 WHERE id = ?", memberSubjectId);
    return ClaimResult.CLAIMED;
  }

  private static Map<String, Object> find(List<Map<String, Object>> rows, long id) {
    return rows.stream()
        .filter(r -> ((Number) r.get("id")).longValue() == id)
        .findFirst()
        .orElse(null);
  }
}
