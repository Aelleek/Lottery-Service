package com.lottery.lottery_service.admin.mfa;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** 인증 앱 코드(RFC 6238 TOTP). 30초 단위, 6자리, HMAC-SHA1이다(D-047). */
public final class Totp {

  /** 시간 단위 길이(초). */
  public static final long STEP_SECONDS = 30;

  private static final String BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

  private Totp() {}

  /** 그 시각의 시간 단위 번호. */
  public static long step(Instant at) {
    return Math.floorDiv(at.getEpochSecond(), STEP_SECONDS);
  }

  /** 시간 단위의 6자리 코드. */
  public static String code(byte[] secret, long step) {
    try {
      Mac mac = Mac.getInstance("HmacSHA1");
      mac.init(new SecretKeySpec(secret, "HmacSHA1"));
      byte[] hash = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());
      int offset = hash[hash.length - 1] & 0x0f;
      int binary =
          ((hash[offset] & 0x7f) << 24)
              | ((hash[offset + 1] & 0xff) << 16)
              | ((hash[offset + 2] & 0xff) << 8)
              | (hash[offset + 3] & 0xff);
      return String.format(Locale.ROOT, "%06d", binary % 1_000_000);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  /** 인증 앱에 입력할 Base32 비밀값(패딩 없음). */
  public static String base32(byte[] data) {
    StringBuilder out = new StringBuilder();
    int buffer = 0;
    int bits = 0;
    for (byte b : data) {
      buffer = (buffer << 8) | (b & 0xff);
      bits += 8;
      while (bits >= 5) {
        out.append(BASE32.charAt((buffer >> (bits - 5)) & 31));
        bits -= 5;
      }
    }
    if (bits > 0) {
      out.append(BASE32.charAt((buffer << (5 - bits)) & 31));
    }
    return out.toString();
  }
}
