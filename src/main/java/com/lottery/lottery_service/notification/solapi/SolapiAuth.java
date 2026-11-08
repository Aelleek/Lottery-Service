package com.lottery.lottery_service.notification.solapi;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * SOLAPI API 키 인증 헤더. 형식은 SOLAPI 공식 SDK(solapi-nodejs {@code authenticator.ts})와 같다: {@code
 * HMAC-SHA256 apiKey=<키>, date=<ISO-8601>, salt=<32자>, signature=<hex HMAC-SHA256(date + salt,
 * 비밀값)>}.
 */
public final class SolapiAuth {

  private static final String ALPHABET =
      "1234567890abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
  private static final int SALT_LENGTH = 32;
  private static final SecureRandom RANDOM = new SecureRandom();

  private SolapiAuth() {}

  /** 새 salt로 헤더를 만든다. */
  public static String header(String apiKey, String apiSecret, Instant now) {
    StringBuilder salt = new StringBuilder(SALT_LENGTH);
    for (int i = 0; i < SALT_LENGTH; i++) {
      salt.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
    }
    return header(apiKey, apiSecret, now, salt.toString());
  }

  /** 주어진 salt로 헤더를 만든다. */
  static String header(String apiKey, String apiSecret, Instant now, String salt) {
    String date =
        DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(
            now.truncatedTo(ChronoUnit.SECONDS).atOffset(ZoneOffset.UTC));
    return "HMAC-SHA256 apiKey="
        + apiKey
        + ", date="
        + date
        + ", salt="
        + salt
        + ", signature="
        + sign(apiSecret, date + salt);
  }

  static String sign(String secret, String data) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }
}
