package com.lottery.lottery_service.notification.solapi;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SolapiAuthTest {

  @Test
  @DisplayName("헤더는 apiKey, ISO-8601 date, salt, hex HMAC-SHA256(date+salt) 순이다")
  void headerFormat() {
    String header =
        SolapiAuth.header(
            "KEY",
            "SECRET",
            Instant.parse("2026-11-08T15:30:00.123Z"),
            "abcdefghijklmnopqrstuvwxyz012345");

    assertThat(header)
        .isEqualTo(
            "HMAC-SHA256 apiKey=KEY, date=2026-11-08T15:30:00Z, salt=abcdefghijklmnopqrstuvwxyz012345,"
                + " signature="
                + SolapiAuth.sign(
                    "SECRET", "2026-11-08T15:30:00Zabcdefghijklmnopqrstuvwxyz012345"));
    assertThat(SolapiAuth.sign("key", "The quick brown fox jumps over the lazy dog"))
        .isEqualTo("f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8");
  }

  @Test
  @DisplayName("salt는 매번 32자 영숫자로 새로 만든다")
  void freshSalt() {
    Instant now = Instant.parse("2026-11-08T15:30:00Z");
    String a = SolapiAuth.header("K", "S", now);
    String b = SolapiAuth.header("K", "S", now);
    assertThat(a).isNotEqualTo(b).matches(".*salt=[0-9a-zA-Z]{32}, signature=[0-9a-f]{64}$");
  }
}
