package com.lottery.lottery_service.notification.web;

import com.lottery.lottery_service.auth.MemberPrincipal;
import com.lottery.lottery_service.common.error.BusinessException;
import com.lottery.lottery_service.common.error.ErrorCode;
import com.lottery.lottery_service.common.web.CanonicalUuid;
import com.lottery.lottery_service.common.web.RequestSubject;
import com.lottery.lottery_service.notification.verify.ContactCipher;
import com.lottery.lottery_service.notification.verify.PhoneMask;
import com.lottery.lottery_service.notification.verify.PreferenceCommandService;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 알림 설정 조회와 동의·철회({@code GET·PUT /me/notification-preference}). 회원만 쓴다. 번호는 가린 값만 준다(D-059). 두 버전은
 * 같은 {@code member_contact.version}이다.
 */
@RestController
@RequestMapping("/api/v1")
@ConditionalOnProperty(prefix = "lottery.api-v1", name = "enabled", havingValue = "true")
public class PreferenceController {

  private final JdbcTemplate jdbc;
  private final ContactCipher cipher;
  private final PreferenceCommandService commands;
  private final Clock clock;

  /** 컨트롤러를 만든다. */
  public PreferenceController(
      JdbcTemplate jdbc, ContactCipher cipher, PreferenceCommandService commands, Clock clock) {
    this.jdbc = jdbc;
    this.cipher = cipher;
    this.commands = commands;
    this.clock = clock;
  }

  /** 지금 알림 설정. 연락처 행이 없으면 꺼진 기본값이다. */
  @GetMapping("/me/notification-preference")
  public ResponseEntity<Preference> preference(Authentication auth) {
    MemberPrincipal member = member(auth);
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(load(member.memberId()));
  }

  /**
   * 알림 수신에 동의하거나 철회한다. 회원은 세션에서만 정한다. {@code expectedVersion}과 {@code verifiedContactVersion}은 같은
   * 값이어야 한다(D-059).
   */
  @PutMapping("/me/notification-preference")
  public ResponseEntity<Success> change(
      Authentication auth,
      @RequestHeader("X-Command-Token") String commandToken,
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @RequestBody Input input) {
    MemberPrincipal member = member(auth);
    RequestSubject subject = RequestSubject.member(auth);
    UUID key;
    long expected;
    long verified;
    try {
      key = CanonicalUuid.parse(idempotencyKey);
      expected = Long.parseLong(input.expectedVersion());
      verified = Long.parseLong(input.verifiedContactVersion());
    } catch (IllegalArgumentException | NullPointerException e) {
      throw new BusinessException(ErrorCode.VALIDATION_FAILED, "입력 형식을 확인해 주세요.");
    }
    if (input.enabled() == null
        || expected < 0
        || expected != verified
        || input.policyVersion() == null
        || input.policyVersion().isBlank()
        || input.policyVersion().length() > 40
        || commandToken.length() > 4096) {
      throw new BusinessException(ErrorCode.VALIDATION_FAILED, "입력을 확인해 주세요.");
    }
    UUID operationId =
        commands.change(
            member.memberId(),
            subject.subjectId(),
            subject.subjectPublicId(),
            commandToken,
            key,
            input.enabled(),
            expected,
            input.policyVersion());
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(new Success(operationId, "SUCCEEDED", load(member.memberId()), clock.instant()));
  }

  private static MemberPrincipal member(Authentication auth) {
    if (auth == null || !(auth.getPrincipal() instanceof MemberPrincipal member)) {
      throw new BusinessException(ErrorCode.UNAUTHENTICATED, "로그인이 필요합니다.");
    }
    return member;
  }

  private Preference load(long memberId) {
    List<Map<String, Object>> rows =
        jdbc.queryForList(
            "SELECT c.address_ciphertext, c.verified_at, c.version, c.notify_enabled,"
                + " e.policy_version FROM member_contact c"
                + " LEFT JOIN consent_event e ON e.id = c.consent_event_id WHERE c.member_id = ?",
            memberId);
    if (rows.isEmpty()) {
      return new Preference(false, "0", "0", false, null, null);
    }
    Map<String, Object> row = rows.get(0);
    byte[] address = (byte[]) row.get("address_ciphertext");
    String version = row.get("version").toString();
    return new Preference(
        Boolean.TRUE.equals(row.get("notify_enabled")),
        version,
        version,
        row.get("verified_at") != null,
        address == null ? null : PhoneMask.mask(cipher.decrypt(memberId, address)),
        (String) row.get("policy_version"));
  }

  /**
   * 요청 본문(openapi {@code PreferenceInput}).
   *
   * @param enabled 수신을 켤지
   * @param expectedVersion 화면이 본 설정 버전(십진 문자열)
   * @param verifiedContactVersion 화면이 본 연락처 버전. {@code expectedVersion}과 같다
   * @param policyVersion 동의 문구 버전
   */
  public record Input(
      Boolean enabled,
      String expectedVersion,
      String verifiedContactVersion,
      String policyVersion) {}

  /**
   * 응답(openapi {@code PreferenceSuccess}).
   *
   * @param operationId 명령 ID
   * @param status SUCCEEDED
   * @param data 바뀐 뒤 설정
   * @param serverTime 서버 시각
   */
  public record Success(UUID operationId, String status, Preference data, Instant serverTime) {}

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
