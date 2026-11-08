package com.lottery.lottery_service.notification.web;

import com.lottery.lottery_service.auth.MemberPrincipal;
import com.lottery.lottery_service.common.error.BusinessException;
import com.lottery.lottery_service.common.error.ErrorCode;
import com.lottery.lottery_service.notification.verify.ContactCipher;
import com.lottery.lottery_service.notification.verify.PhoneMask;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 알림 설정 조회({@code GET /me/notification-preference}). 회원만 쓴다. 번호는 가린 값만 준다(D-059). 두 버전은 같은 {@code
 * member_contact.version}이다.
 */
@RestController
@RequestMapping("/api/v1")
@ConditionalOnProperty(prefix = "lottery.api-v1", name = "enabled", havingValue = "true")
public class PreferenceController {

  private final JdbcTemplate jdbc;
  private final ContactCipher cipher;

  /** 컨트롤러를 만든다. */
  public PreferenceController(JdbcTemplate jdbc, ContactCipher cipher) {
    this.jdbc = jdbc;
    this.cipher = cipher;
  }

  /** 지금 알림 설정. 연락처 행이 없으면 꺼진 기본값이다. */
  @GetMapping("/me/notification-preference")
  public ResponseEntity<Preference> preference(Authentication auth) {
    if (auth == null || !(auth.getPrincipal() instanceof MemberPrincipal member)) {
      throw new BusinessException(ErrorCode.UNAUTHENTICATED, "로그인이 필요합니다.");
    }
    List<Map<String, Object>> rows =
        jdbc.queryForList(
            "SELECT c.address_ciphertext, c.verified_at, c.version, c.notify_enabled,"
                + " e.policy_version FROM member_contact c"
                + " LEFT JOIN consent_event e ON e.id = c.consent_event_id WHERE c.member_id = ?",
            member.memberId());
    Preference body;
    if (rows.isEmpty()) {
      body = new Preference(false, "0", "0", false, null, null);
    } else {
      Map<String, Object> row = rows.get(0);
      byte[] address = (byte[]) row.get("address_ciphertext");
      String version = row.get("version").toString();
      body =
          new Preference(
              Boolean.TRUE.equals(row.get("notify_enabled")),
              version,
              version,
              row.get("verified_at") != null,
              address == null ? null : PhoneMask.mask(cipher.decrypt(address)),
              (String) row.get("policy_version"));
    }
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
  }

  /**
   * 알림 설정(openapi {@code Preference}).
   *
   * @param enabled 수신 켜짐
   * @param version 설정 버전
   * @param contactVersion 연락처 버전(설정 버전과 같다)
   * @param verified 번호 인증을 마쳤는지
   * @param maskedPhoneNumber 가린 번호
   * @param policyVersion 동의한 정책 버전
   */
  public record Preference(
      boolean enabled,
      String version,
      String contactVersion,
      boolean verified,
      String maskedPhoneNumber,
      String policyVersion) {}
}
