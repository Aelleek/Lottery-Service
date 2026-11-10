package com.lottery.lottery_service.common.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lottery.lottery_service.common.error.BusinessException;
import com.lottery.lottery_service.common.error.ErrorCode;
import com.lottery.lottery_service.support.MutableClock;
import com.lottery.lottery_service.support.TestSecrets;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CommandTokenCodecTest {

  private static final Instant T0 = Instant.parse("2026-11-08T01:00:00Z");
  private static final UUID SUBJECT = UUID.fromString("00000000-0000-0000-0000-000000000001");

  private final MutableClock clock = new MutableClock(T0);
  private final CommandTokenCodec codec = new CommandTokenCodec(TestSecrets.keys(), clock);

  @Test
  @DisplayName("발급한 토큰은 같은 주체·명령으로 확인되고 회차·일정 버전과 24시간 만료가 묶인다")
  void roundTrip() {
    String token = codec.issue(SUBJECT, "RECOMMEND", 1200, 3L);

    CommandToken parsed = codec.verify(token, SUBJECT, "RECOMMEND");

    assertThat(parsed.roundNo()).isEqualTo(1200);
    assertThat(parsed.scheduleVersion()).isEqualTo(3L);
    assertThat(parsed.issuedAt()).isEqualTo(T0);
    assertThat(parsed.expiresAt()).isEqualTo(T0.plus(CommandTokenCodec.VALIDITY));
    assertThat(parsed.expiredAt(parsed.expiresAt().minusMillis(1))).isFalse();
    assertThat(parsed.expiredAt(parsed.expiresAt())).isTrue();
    assertThat(token).doesNotContain(SUBJECT.toString());
  }

  @Test
  @DisplayName("다른 주체, 다른 명령, 바뀐 내용·서명, 다른 키, 형식이 틀린 토큰은 FORBIDDEN")
  void rejectsInvalid() {
    String token = codec.issue(SUBJECT, "PURCHASE", null, null);
    String[] parts = token.split("\\.");
    String otherPayload = codec.issue(UUID.randomUUID(), "PURCHASE", null, null).split("\\.")[1];
    CommandTokenCodec otherKey =
        new CommandTokenCodec(
            TestSecrets.keys(
                "another-command-token-key-0000000", "guest-credential-test-key-012345"),
            clock);

    assertForbidden(() -> codec.verify(token, UUID.randomUUID(), "PURCHASE"));
    assertForbidden(() -> codec.verify(token, SUBJECT, "RECOMMEND"));
    assertForbidden(
        () -> codec.verify(parts[0] + "." + otherPayload + "." + parts[2], SUBJECT, "PURCHASE"));
    assertForbidden(() -> codec.verify(parts[0] + "." + parts[1] + ".AAAA", SUBJECT, "PURCHASE"));
    assertForbidden(() -> codec.verify("v9." + parts[1] + "." + parts[2], SUBJECT, "PURCHASE"));
    assertForbidden(() -> otherKey.verify(token, SUBJECT, "PURCHASE"));
    assertForbidden(() -> codec.verify("garbage", SUBJECT, "PURCHASE"));
    assertForbidden(() -> codec.verify(null, SUBJECT, "PURCHASE"));
  }

  @Test
  @DisplayName("만료된 토큰도 서명이 맞으면 읽는다. 만료 처리는 멱등 기록을 본 뒤 한다")
  void expiredTokenStillParses() {
    String token = codec.issue(SUBJECT, "PURCHASE", null, null);
    clock.set(T0.plus(CommandTokenCodec.VALIDITY).plusSeconds(1));

    assertThat(codec.verify(token, SUBJECT, "PURCHASE").expiredAt(clock.instant())).isTrue();
  }

  private static void assertForbidden(Runnable call) {
    assertThatThrownBy(call::run)
        .isInstanceOfSatisfying(
            BusinessException.class, e -> assertThat(e.getCode()).isEqualTo(ErrorCode.FORBIDDEN));
  }
}
