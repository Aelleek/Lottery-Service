package com.lottery.lottery_service.acceptance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lottery.lottery_service.auth.CsrfTokens;
import com.lottery.lottery_service.auth.GuestPrincipal;
import com.lottery.lottery_service.common.command.CommandTokenCodec;
import com.lottery.lottery_service.common.error.BusinessException;
import com.lottery.lottery_service.common.secret.SecretKeys;
import com.lottery.lottery_service.common.secret.SecretProperties;
import com.lottery.lottery_service.support.MutableClock;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** T64: 서명 키에 새 버전을 더하고 옛 버전을 지우면 옛 토큰은 거부되고 새 토큰은 통과한다(C-30). */
class KeyRotationTest {

  private static final String OLD = "rotation-old-key-0123456789abcdef";
  private static final String NEW = "rotation-new-key-0123456789abcdef";

  private final MutableClock clock = new MutableClock(Instant.parse("2026-11-08T15:00:00Z"));
  private final UUID subject = UUID.randomUUID();
  private final GuestPrincipal guest =
      new GuestPrincipal(1, subject, Instant.parse("2026-12-15T00:00:00Z"));

  @Test
  @DisplayName("교체 중(두 버전)에는 옛 토큰도 통과하고, 옛 버전을 지우면 옛 명령 토큰·CSRF 토큰은 거부된다")
  void rotation() {
    SecretKeys before = keys(ring(1, Map.of(1, OLD)));
    SecretKeys during = keys(ring(2, Map.of(1, OLD, 2, NEW)));
    SecretKeys after = keys(ring(2, Map.of(2, NEW)));
    String oldCommand = new CommandTokenCodec(before, clock).issue(subject, "WITHDRAW", null, null);
    String oldCsrf = new CsrfTokens(before, clock).issue(guest);

    assertThat(new CommandTokenCodec(during, clock).verify(oldCommand, subject, "WITHDRAW"))
        .isNotNull();
    assertThat(new CsrfTokens(during, clock).verify(oldCsrf, guest)).isTrue();

    assertThatThrownBy(
            () -> new CommandTokenCodec(after, clock).verify(oldCommand, subject, "WITHDRAW"))
        .isInstanceOf(BusinessException.class);
    assertThat(new CsrfTokens(after, clock).verify(oldCsrf, guest)).isFalse();

    String newCommand = new CommandTokenCodec(after, clock).issue(subject, "WITHDRAW", null, null);
    assertThat(new CommandTokenCodec(after, clock).verify(newCommand, subject, "WITHDRAW"))
        .isNotNull();
    assertThat(
            new CsrfTokens(after, clock).verify(new CsrfTokens(after, clock).issue(guest), guest))
        .isTrue();
  }

  private static SecretProperties.Ring ring(int current, Map<Integer, String> keys) {
    Map<Integer, String> encoded = new java.util.HashMap<>();
    keys.forEach((v, k) -> encoded.put(v, Base64.getEncoder().encodeToString(k.getBytes())));
    return new SecretProperties.Ring(current, encoded);
  }

  private static SecretKeys keys(SecretProperties.Ring ring) {
    return new SecretKeys(new SecretProperties(ring, null, null, null, ring, null));
  }
}
