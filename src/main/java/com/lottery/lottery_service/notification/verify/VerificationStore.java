package com.lottery.lottery_service.notification.verify;

import static com.lottery.lottery_service.common.db.DbValues.bytes;
import static com.lottery.lottery_service.common.db.DbValues.instant;
import static com.lottery.lottery_service.common.db.DbValues.utc;

import com.lottery.lottery_service.common.error.BusinessException;
import com.lottery.lottery_service.common.error.ErrorCode;
import com.lottery.lottery_service.common.error.ErrorDetails;
import com.lottery.lottery_service.common.secret.KeyRing;
import com.lottery.lottery_service.common.secret.SecretKeys;
import com.lottery.lottery_service.common.time.SeoulTime;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 수신 번호 인증 코드(02 6.4절, 05 8.8절). 6자리 코드는 암호학적 난수로 만들고 HMAC(요청 ID, 목적, 대상, 코드)으로만 저장한다. 5분 유효, 오답
 * 5회, 재발급 간격 60초, 번호별·계정별 하루 5회다. 다시 발급하면 이전 요청은 무효가 된다. 호출한 쪽 트랜잭션 안에서 부른다. 발송은 트랜잭션 밖에서 한다.
 */
@Component
public class VerificationStore {

  /** 코드 유효기간. */
  public static final Duration VALIDITY = Duration.ofMinutes(5);

  /** 오답 상한. */
  public static final int MAX_ATTEMPTS = 5;

  /** 재발급 간격. */
  public static final Duration RESEND_GAP = Duration.ofSeconds(60);

  /** 하루 발급 상한(번호별, 계정별). */
  public static final int DAILY_LIMIT = 5;

  private static final String PURPOSE = "CONTACT";

  private final JdbcTemplate jdbc;
  private final SecretKeys secrets;
  private final ContactCipher cipher;
  private final Clock clock;
  private final SecureRandom random = new SecureRandom();

  /** 저장소를 만든다. */
  public VerificationStore(
      JdbcTemplate jdbc, SecretKeys secrets, ContactCipher cipher, Clock clock) {
    this.jdbc = jdbc;
    this.secrets = secrets;
    this.cipher = cipher;
    this.clock = clock;
  }

  /**
   * 인증 코드를 발급한다.
   *
   * @param phone 정규화한 수신 번호
   * @param contactVersion 화면이 본 연락처 버전
   * @throws BusinessException 간격 전이거나 하루 상한이면 {@code RATE_LIMITED}(다시 시도할 시각 포함)
   */
  public IssuedChallenge issue(long memberId, String phone, long contactVersion) {
    Instant now = clock.instant();
    KeyRing ring = secrets.verificationCode();
    byte[] target = ring.sign(("PHONE|" + phone).getBytes(StandardCharsets.UTF_8));
    byte[] memberScope = ring.sign(("MEMBER|" + memberId).getBytes(StandardCharsets.UTF_8));
    LocalDate day = SeoulTime.dateOf(now);
    consume("MEMBER", memberScope, day, now);
    consume("PHONE", target, day, now);
    jdbc.update(
        "UPDATE verification_challenge SET state = 'REVOKED', code_hmac = NULL"
            + " WHERE member_id = ? AND state = 'ACTIVE'",
        memberId);
    UUID publicId = UUID.randomUUID();
    String code = String.format("%06d", random.nextInt(1_000_000));
    Instant expiresAt = now.plus(VALIDITY);
    jdbc.update(
        "INSERT INTO verification_challenge (public_id, member_id, target_hmac, target_ciphertext,"
            + " code_hmac, key_version, expected_contact_version, state, expires_at)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?)",
        bytes(publicId),
        memberId,
        target,
        cipher.encrypt(memberId, phone),
        codeHmac(ring, ring.currentVersion(), publicId, target, code),
        ring.currentVersion(),
        contactVersion,
        utc(expiresAt));
    long id =
        jdbc.queryForObject(
            "SELECT id FROM verification_challenge WHERE public_id = ?",
            Long.class,
            bytes(publicId));
    return new IssuedChallenge(id, publicId, code, expiresAt);
  }

  /**
   * 코드를 확인한다. 요청 행을 잠근 채 시도 수, 기한, 사용 여부, 연락처 버전을 한 번에 본다. 맞으면 번호를 인증하고 알림 수신은 다시 동의받을 때까지 꺼 둔다.
   * 실패도 결과로 돌려주므로 호출한 쪽은 트랜잭션을 커밋한 뒤 {@link VerificationResult#errorCode()}로 오류를 답한다.
   */
  public VerificationResult verify(long memberId, UUID publicId, String code, long contactVersion) {
    List<Map<String, Object>> rows =
        jdbc.queryForList(
            "SELECT id, target_hmac, target_ciphertext, code_hmac, key_version,"
                + " expected_contact_version, state, attempts, expires_at FROM verification_challenge"
                + " WHERE public_id = ? AND member_id = ? FOR UPDATE",
            bytes(publicId),
            memberId);
    if (rows.isEmpty()) {
      return VerificationResult.EXPIRED;
    }
    Map<String, Object> row = rows.get(0);
    long id = ((Number) row.get("id")).longValue();
    String state = (String) row.get("state");
    if ("LOCKED".equals(state)) {
      return VerificationResult.LOCKED;
    }
    Instant now = clock.instant();
    if (!"ACTIVE".equals(state) || !now.isBefore(instant((LocalDateTime) row.get("expires_at")))) {
      jdbc.update(
          "UPDATE verification_challenge SET state = IF(state = 'ACTIVE', 'EXPIRED', state),"
              + " code_hmac = NULL WHERE id = ?",
          id);
      return VerificationResult.EXPIRED;
    }
    if (((Number) row.get("expected_contact_version")).longValue() != contactVersion) {
      return VerificationResult.CONTACT_CHANGED;
    }
    KeyRing ring = secrets.verificationCode();
    byte[] expected = (byte[]) row.get("code_hmac");
    byte[] given =
        codeHmac(
            ring,
            ((Number) row.get("key_version")).intValue(),
            publicId,
            (byte[]) row.get("target_hmac"),
            code == null ? "" : code);
    if (!MessageDigest.isEqual(expected, given)) {
      int attempts = ((Number) row.get("attempts")).intValue() + 1;
      if (attempts >= MAX_ATTEMPTS) {
        jdbc.update(
            "UPDATE verification_challenge SET attempts = ?, state = 'LOCKED', code_hmac = NULL"
                + " WHERE id = ?",
            attempts,
            id);
        return VerificationResult.LOCKED;
      }
      jdbc.update("UPDATE verification_challenge SET attempts = ? WHERE id = ?", attempts, id);
      return VerificationResult.WRONG;
    }
    jdbc.update(
        "UPDATE verification_challenge SET state = 'CONSUMED', consumed_at = ?, code_hmac = NULL"
            + " WHERE id = ?",
        utc(now),
        id);
    jdbc.update(
        "INSERT INTO member_contact (member_id, address_ciphertext, key_version, verified_at,"
            + " notify_enabled, consent_event_id) VALUES (?, ?, ?, ?, FALSE, NULL)"
            + " ON DUPLICATE KEY UPDATE address_ciphertext = VALUES(address_ciphertext),"
            + " key_version = VALUES(key_version), verified_at = VALUES(verified_at),"
            + " notify_enabled = FALSE, consent_event_id = NULL, version = version + 1",
        memberId,
        row.get("target_ciphertext"),
        secrets.contact().currentVersion(),
        utc(now));
    return VerificationResult.VERIFIED;
  }

  private void consume(String scope, byte[] scopeHmac, LocalDate day, Instant now) {
    jdbc.update(
        "INSERT INTO verification_rate_bucket (scope_type, scope_hmac, business_date, send_count,"
            + " next_send_at, expires_at) VALUES (?, ?, ?, 0, ?, ?)"
            + " ON DUPLICATE KEY UPDATE scope_type = scope_type",
        scope,
        scopeHmac,
        day,
        utc(now),
        utc(SeoulTime.startOfDay(day.plusDays(2))));
    Map<String, Object> bucket =
        jdbc.queryForMap(
            "SELECT send_count, next_send_at FROM verification_rate_bucket WHERE scope_type = ?"
                + " AND scope_hmac = ? AND business_date = ? FOR UPDATE",
            scope,
            scopeHmac,
            day);
    Instant nextAt = instant((LocalDateTime) bucket.get("next_send_at"));
    int count = ((Number) bucket.get("send_count")).intValue();
    if (count >= DAILY_LIMIT) {
      throw limited(SeoulTime.startOfDay(day.plusDays(1)));
    }
    if (now.isBefore(nextAt)) {
      throw limited(nextAt);
    }
    jdbc.update(
        "UPDATE verification_rate_bucket SET send_count = send_count + 1, next_send_at = ?"
            + " WHERE scope_type = ? AND scope_hmac = ? AND business_date = ?",
        utc(now.plus(RESEND_GAP)),
        scope,
        scopeHmac,
        day);
  }

  private static byte[] codeHmac(
      KeyRing ring, int version, UUID publicId, byte[] target, String code) {
    String message = publicId + "|" + PURPOSE + "|" + HexFormat.of().formatHex(target) + "|" + code;
    return ring.sign(version, message.getBytes(StandardCharsets.UTF_8));
  }

  private static BusinessException limited(Instant retryAt) {
    return new BusinessException(
        ErrorCode.RATE_LIMITED, "잠시 뒤 다시 요청해 주세요.", ErrorDetails.retryAt(retryAt));
  }
}
