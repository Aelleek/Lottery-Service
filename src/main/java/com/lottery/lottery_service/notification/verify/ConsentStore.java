package com.lottery.lottery_service.notification.verify;

import static com.lottery.lottery_service.common.db.DbValues.utc;

import com.lottery.lottery_service.common.error.BusinessException;
import com.lottery.lottery_service.common.error.ErrorCode;
import com.lottery.lottery_service.common.error.ErrorDetails;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 결과 알림 수신 동의(02 6.4절). 인증을 마친 번호에만 동의를 받고, 동의할 때마다 새 동의 기록을 남긴다. 철회하면 새 발송을 바로 막는다. 호출한 쪽 트랜잭션 안에서
 * 부른다.
 */
@Component
public class ConsentStore {

  private final JdbcTemplate jdbc;
  private final Clock clock;

  /** 저장소를 만든다. */
  public ConsentStore(JdbcTemplate jdbc, Clock clock) {
    this.jdbc = jdbc;
    this.clock = clock;
  }

  /**
   * 알림 수신에 동의한다.
   *
   * @param expectedContactVersion 화면이 본 연락처 버전
   * @throws BusinessException 인증된 번호가 없으면 {@code VALIDATION_FAILED}, 연락처 버전이 다르면 {@code
   *     CONTACT_CHANGED}
   */
  public void grant(long memberId, String policyVersion, long expectedContactVersion) {
    Map<String, Object> contact = lockContact(memberId, expectedContactVersion);
    if (contact.get("verified_at") == null) {
      throw new BusinessException(ErrorCode.VALIDATION_FAILED, "번호 인증을 먼저 해 주세요.");
    }
    long consent = insertConsent(memberId, true, policyVersion);
    jdbc.update(
        "UPDATE member_contact SET notify_enabled = TRUE, consent_event_id = ?, version = version + 1"
            + " WHERE member_id = ?",
        consent,
        memberId);
  }

  /** 동의를 철회한다. 수신을 끄고 동의 참조를 비운다. 연락처가 없으면 아무것도 하지 않는다. */
  public void withdraw(long memberId, String policyVersion) {
    List<Map<String, Object>> rows =
        jdbc.queryForList(
            "SELECT member_id FROM member_contact WHERE member_id = ? FOR UPDATE", memberId);
    if (rows.isEmpty()) {
      return;
    }
    insertConsent(memberId, false, policyVersion);
    jdbc.update(
        "UPDATE member_contact SET notify_enabled = FALSE, consent_event_id = NULL, version = version + 1"
            + " WHERE member_id = ?",
        memberId);
  }

  private Map<String, Object> lockContact(long memberId, long expectedVersion) {
    List<Map<String, Object>> rows =
        jdbc.queryForList(
            "SELECT version, verified_at FROM member_contact WHERE member_id = ? FOR UPDATE",
            memberId);
    if (rows.isEmpty()) {
      throw new BusinessException(ErrorCode.VALIDATION_FAILED, "번호 인증을 먼저 해 주세요.");
    }
    long version = ((Number) rows.get(0).get("version")).longValue();
    if (version != expectedVersion) {
      throw new BusinessException(
          ErrorCode.CONTACT_CHANGED,
          "연락처가 바뀌었습니다. 다시 확인해 주세요.",
          ErrorDetails.currentVersion(version));
    }
    return rows.get(0);
  }

  private long insertConsent(long memberId, boolean granted, String policyVersion) {
    jdbc.update(
        "INSERT INTO consent_event (member_id, granted, policy_version, occurred_at) VALUES (?, ?, ?, ?)",
        memberId,
        granted,
        policyVersion,
        utc(clock.instant()));
    return jdbc.queryForObject(
        "SELECT MAX(id) FROM consent_event WHERE member_id = ?", Long.class, memberId);
  }
}
