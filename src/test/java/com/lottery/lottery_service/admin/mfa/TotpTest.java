package com.lottery.lottery_service.admin.mfa;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TotpTest {

  private static final byte[] RFC_SECRET =
      "12345678901234567890".getBytes(StandardCharsets.US_ASCII);

  @Test
  @DisplayName("RFC 6238 SHA1 시험값의 끝 6자리와 같다")
  void rfcVectors() {
    assertThat(Totp.code(RFC_SECRET, Totp.step(Instant.ofEpochSecond(59)))).isEqualTo("287082");
    assertThat(Totp.code(RFC_SECRET, Totp.step(Instant.ofEpochSecond(1_111_111_109))))
        .isEqualTo("081804");
    assertThat(Totp.code(RFC_SECRET, Totp.step(Instant.ofEpochSecond(1_234_567_890))))
        .isEqualTo("005924");
    assertThat(Totp.code(RFC_SECRET, Totp.step(Instant.ofEpochSecond(20_000_000_000L))))
        .isEqualTo("353130");
  }

  @Test
  @DisplayName("Base32는 RFC 4648 알파벳이고 패딩이 없다")
  void base32() {
    assertThat(Totp.base32("foobar".getBytes(StandardCharsets.US_ASCII))).isEqualTo("MZXW6YTBOI");
    assertThat(Totp.base32(RFC_SECRET)).isEqualTo("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ");
  }
}
