package com.lottery.lottery_service.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.lottery.lottery_service.support.MutableClock;
import com.lottery.lottery_service.support.TestSecrets;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CsrfTokensTest {

  private static final Instant T0 = Instant.parse("2026-11-08T14:00:00Z");

  private final MutableClock clock = new MutableClock(T0);
  private final CsrfTokens tokens = new CsrfTokens(TestSecrets.keys(), clock);
  private final GuestPrincipal guest =
      new GuestPrincipal(1, UUID.randomUUID(), T0.plusSeconds(37L * 86400));
  private final MemberPrincipal member =
      new MemberPrincipal(7, UUID.randomUUID(), 11, UUID.randomUUID(), 0, OAuthProvider.GOOGLE);

  @Test
  @DisplayName("같은 자격과 세션에 묶은 토큰만 맞다")
  void bindsToCredentialAndSession() {
    String guestToken = tokens.issue(guest);
    String memberToken = tokens.issue(member, "session-a");

    assertThat(tokens.verify(guestToken, guest)).isTrue();
    assertThat(tokens.verify(memberToken, member, "session-a")).isTrue();

    GuestPrincipal rotated =
        new GuestPrincipal(
            guest.subjectId(), guest.subjectPublicId(), guest.credentialExpiresAt().plusSeconds(5));
    assertThat(tokens.verify(guestToken, rotated)).isFalse();
    assertThat(tokens.verify(memberToken, member, "session-b")).isFalse();
    MemberPrincipal revoked =
        new MemberPrincipal(
            7, member.memberPublicId(), 11, member.subjectPublicId(), 1, OAuthProvider.GOOGLE);
    assertThat(tokens.verify(memberToken, revoked, "session-a")).isFalse();
    assertThat(tokens.verify(guestToken, member, "session-a")).isFalse();
  }

  @Test
  @DisplayName("발급 뒤 24시간이 지나면 받지 않는다")
  void expiresAfter24Hours() {
    String token = tokens.issue(guest);

    clock.set(T0.plus(CsrfTokens.VALIDITY).minusSeconds(1));
    assertThat(tokens.verify(token, guest)).isTrue();
    clock.set(T0.plus(CsrfTokens.VALIDITY));
    assertThat(tokens.verify(token, guest)).isFalse();
  }

  @Test
  @DisplayName("형식이 틀리거나 서명을 바꾸거나 모르는 키 버전이면 받지 않는다")
  void rejectsMalformed() {
    String token = tokens.issue(guest);
    String[] parts = token.split("\\.");

    assertThat(tokens.verify(null, guest)).isFalse();
    assertThat(tokens.verify("", guest)).isFalse();
    assertThat(tokens.verify("garbage", guest)).isFalse();
    assertThat(tokens.verify("v9." + parts[1] + "." + parts[2], guest)).isFalse();
    assertThat(
            tokens.verify(parts[0] + "." + (Long.parseLong(parts[1]) - 1) + "." + parts[2], guest))
        .isFalse();
    assertThat(tokens.verify(parts[0] + "." + parts[1] + ".AAAA", guest)).isFalse();
    assertThat(tokens.verify(token + "x".repeat(200), guest)).isFalse();
  }
}
