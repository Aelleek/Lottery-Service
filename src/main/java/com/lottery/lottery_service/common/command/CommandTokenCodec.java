package com.lottery.lottery_service.common.command;

import com.lottery.lottery_service.common.error.BusinessException;
import com.lottery.lottery_service.common.error.ErrorCode;
import com.lottery.lottery_service.common.secret.KeyRing;
import com.lottery.lottery_service.common.secret.SecretKeys;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * 명령 토큰을 발급하고 서명을 확인한다. 형식은 {@code v<키 버전>.<내용>.<서명>}이고, 내용과 서명은 Base64URL이다. 서명은 명령 토큰 서명 키의
 * HMAC-SHA256이다. 유효기간은 발급 후 24시간이다.
 */
@Component
public class CommandTokenCodec {

  /** 토큰 유효기간. */
  public static final Duration VALIDITY = Duration.ofHours(24);

  private static final Pattern NAME = Pattern.compile("[A-Z_]{1,40}");
  private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
  private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

  private final SecretKeys secrets;
  private final Clock clock;

  /** 코덱을 만든다. */
  public CommandTokenCodec(SecretKeys secrets, Clock clock) {
    this.secrets = secrets;
    this.clock = clock;
  }

  /** 새 토큰을 발급한다. 추천 명령이면 회차와 일정 버전을 묶는다. */
  public String issue(
      UUID subjectPublicId, String operation, Integer roundNo, Long scheduleVersion) {
    if (!NAME.matcher(operation).matches() || (roundNo == null) != (scheduleVersion == null)) {
      throw new IllegalArgumentException("invalid command token claims");
    }
    Instant now = clock.instant();
    String body =
        String.join(
            "|",
            UUID.randomUUID().toString(),
            subjectPublicId.toString(),
            operation,
            Long.toString(now.toEpochMilli()),
            Long.toString(now.plus(VALIDITY).toEpochMilli()),
            roundNo == null ? "" : roundNo.toString(),
            scheduleVersion == null ? "" : scheduleVersion.toString());
    KeyRing ring = secrets.commandToken();
    String payload = ENCODER.encodeToString(body.getBytes(StandardCharsets.UTF_8));
    String signed = "v" + ring.currentVersion() + "." + payload;
    return signed
        + "."
        + ENCODER.encodeToString(ring.sign(signed.getBytes(StandardCharsets.UTF_8)));
  }

  /**
   * 서명을 확인하고 내용을 읽는다. 만료 여부는 여기서 거부하지 않는다. 만료된 토큰이라도 완료된 결과는 돌려줄 수 있어서 호출한 쪽이 판단한다.
   *
   * @throws BusinessException 형식이나 서명이 틀렸거나, 주체나 명령 종류가 다르면 {@code FORBIDDEN}
   */
  public CommandToken verify(String token, UUID subjectPublicId, String operation) {
    CommandToken parsed = parse(token);
    if (!parsed.subjectPublicId().equals(subjectPublicId)
        || !parsed.operation().equals(operation)) {
      throw forbidden();
    }
    return parsed;
  }

  private CommandToken parse(String token) {
    if (token == null || token.length() > 4096) {
      throw forbidden();
    }
    String[] parts = token.split("\\.", -1);
    if (parts.length != 3 || !parts[0].matches("v\\d{1,6}")) {
      throw forbidden();
    }
    try {
      int version = Integer.parseInt(parts[0].substring(1));
      byte[] expected =
          secrets
              .commandToken()
              .sign(version, (parts[0] + "." + parts[1]).getBytes(StandardCharsets.UTF_8));
      if (!MessageDigest.isEqual(expected, DECODER.decode(parts[2]))) {
        throw forbidden();
      }
      String[] f = new String(DECODER.decode(parts[1]), StandardCharsets.UTF_8).split("\\|", -1);
      if (f.length != 7) {
        throw forbidden();
      }
      return new CommandToken(
          UUID.fromString(f[0]),
          UUID.fromString(f[1]),
          f[2],
          Instant.ofEpochMilli(Long.parseLong(f[3])),
          Instant.ofEpochMilli(Long.parseLong(f[4])),
          f[5].isEmpty() ? null : Integer.valueOf(f[5]),
          f[6].isEmpty() ? null : Long.valueOf(f[6]));
    } catch (IllegalArgumentException e) {
      throw forbidden();
    }
  }

  private static BusinessException forbidden() {
    return new BusinessException(ErrorCode.FORBIDDEN, "허용되지 않은 요청입니다.");
  }
}
