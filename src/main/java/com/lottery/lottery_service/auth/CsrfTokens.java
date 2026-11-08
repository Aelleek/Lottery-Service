package com.lottery.lottery_service.auth;

import com.lottery.lottery_service.common.secret.KeyRing;
import com.lottery.lottery_service.common.secret.SecretKeys;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import org.springframework.stereotype.Component;

/**
 * CSRF 토큰을 만들고 확인한다(D-051). 토큰은 비회원 자격이나 회원 세션에 묶은 서버 서명 값이고 서버에 저장하지 않는다.
 *
 * <p>형식은 {@code v<키 버전>.<발급 초>.<서명>}이다. 서명은 HMAC(묶는 값, 발급 초)다. 비회원은 주체 외부 ID와 자격 만료 시각에, 회원은 회원
 * ID·{@code auth_version}·세션 ID에 묶는다. 자격을 회전하거나 다시 로그인하면 옛 토큰은 맞지 않는다. 발급 뒤 24시간이 지나면 받지 않는다.
 */
@Component
public class CsrfTokens {

  /** 토큰 유효 시간. */
  public static final Duration VALIDITY = Duration.ofHours(24);

  private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
  private static final int MAX_TOKEN_LENGTH = 128;

  private final SecretKeys secrets;
  private final Clock clock;

  /** 키와 시계로 만든다. */
  public CsrfTokens(SecretKeys secrets, Clock clock) {
    this.secrets = secrets;
    this.clock = clock;
  }

  /** 비회원 자격에 묶은 토큰을 만든다. */
  public String issue(GuestPrincipal guest) {
    return issue(guestBinding(guest));
  }

  /** 회원 세션에 묶은 토큰을 만든다. */
  public String issue(MemberPrincipal member, String sessionId) {
    return issue(memberBinding(member, sessionId));
  }

  /** 비회원 요청의 토큰이 맞는지 본다. */
  public boolean verify(String token, GuestPrincipal guest) {
    return verify(token, guestBinding(guest));
  }

  /** 회원 요청의 토큰이 맞는지 본다. */
  public boolean verify(String token, MemberPrincipal member, String sessionId) {
    return verify(token, memberBinding(member, sessionId));
  }

  private String issue(String binding) {
    KeyRing ring = secrets.csrfToken();
    long issuedAt = clock.instant().getEpochSecond();
    int version = ring.currentVersion();
    return "v"
        + version
        + "."
        + issuedAt
        + "."
        + B64.encodeToString(sign(ring, version, binding, issuedAt));
  }

  private boolean verify(String token, String binding) {
    if (token == null || token.isEmpty() || token.length() > MAX_TOKEN_LENGTH) {
      return false;
    }
    String[] parts = token.split("\\.", -1);
    if (parts.length != 3 || parts[0].length() < 2 || parts[0].charAt(0) != 'v') {
      return false;
    }
    int version;
    long issuedAt;
    byte[] signature;
    try {
      version = Integer.parseInt(parts[0].substring(1));
      issuedAt = Long.parseLong(parts[1]);
      signature = Base64.getUrlDecoder().decode(parts[2]);
    } catch (IllegalArgumentException e) {
      return false;
    }
    Instant now = clock.instant();
    Instant issued = Instant.ofEpochSecond(issuedAt);
    if (issued.isAfter(now.plusSeconds(60)) || !issued.plus(VALIDITY).isAfter(now)) {
      return false;
    }
    byte[] expected;
    try {
      expected = sign(secrets.csrfToken(), version, binding, issuedAt);
    } catch (IllegalArgumentException e) {
      return false;
    }
    return MessageDigest.isEqual(expected, signature);
  }

  private static byte[] sign(KeyRing ring, int version, String binding, long issuedAt) {
    return ring.sign(version, (binding + "|" + issuedAt).getBytes(StandardCharsets.UTF_8));
  }

  private static String guestBinding(GuestPrincipal guest) {
    return "G|" + guest.subjectPublicId() + "|" + guest.credentialExpiresAt().getEpochSecond();
  }

  private static String memberBinding(MemberPrincipal member, String sessionId) {
    return "M|" + member.memberId() + "|" + member.authVersion() + "|" + sha256(sessionId);
  }

  private static String sha256(String value) {
    try {
      return B64.encodeToString(
          MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
