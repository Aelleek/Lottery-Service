package com.lottery.lottery_service.common.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lottery.lottery_service.common.error.BusinessException;
import com.lottery.lottery_service.common.error.ErrorCode;
import com.lottery.lottery_service.support.TestSecrets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CommandTokenServiceTest {

  private static final Instant T0 = Instant.parse("2026-11-08T01:00:00Z");
  private final Clock clock = Clock.fixed(T0, ZoneOffset.UTC);
  private final CommandTokenCodec codec = new CommandTokenCodec(TestSecrets.keys(), clock);
  private final CommandTokenService service = new CommandTokenService(codec);
  private final UUID subject = UUID.randomUUID();

  @Test
  @DisplayName("추천 토큰은 회차와 일정 버전을 묶고 24시간 뒤 만료, 다른 명령은 회차를 묶지 않는다")
  void issues() {
    CommandTokenService.IssuedToken recommend =
        service.issue(subject, false, "RECOMMEND", 1200, 3L);
    CommandToken parsed = codec.verify(recommend.token(), subject, "RECOMMEND");

    assertThat(parsed.roundNo()).isEqualTo(1200);
    assertThat(parsed.scheduleVersion()).isEqualTo(3L);
    assertThat(recommend.expiresAt()).isEqualTo(T0.plus(CommandTokenCodec.VALIDITY));
    assertThat(recommend.toString()).doesNotContain(recommend.token());

    CommandToken cancel =
        codec.verify(
            service.issue(subject, true, "CANCEL_PURCHASE", 1200, 3L).token(),
            subject,
            "CANCEL_PURCHASE");
    assertThat(cancel.roundNo()).isNull();
  }

  @Test
  @DisplayName("비회원은 추천만, 없는 명령은 FORBIDDEN, 추천인데 회차·버전이 없으면 VALIDATION_FAILED")
  void rejects() {
    assertCode(
        () -> service.issue(subject, false, "CANCEL_PURCHASE", null, null), ErrorCode.FORBIDDEN);
    assertCode(
        () -> service.issue(subject, true, "ADMIN_PUBLISH", null, null), ErrorCode.FORBIDDEN);
    assertCode(() -> service.issue(subject, true, null, null, null), ErrorCode.FORBIDDEN);
    assertCode(
        () -> service.issue(subject, true, "RECOMMEND", null, 1L), ErrorCode.VALIDATION_FAILED);
    assertCode(
        () -> service.issue(subject, false, "RECOMMEND", 1200, null), ErrorCode.VALIDATION_FAILED);
  }

  private static void assertCode(ThrowingCallable call, ErrorCode code) {
    assertThatThrownBy(call)
        .isInstanceOfSatisfying(
            BusinessException.class, e -> assertThat(e.getCode()).isEqualTo(code));
  }
}
