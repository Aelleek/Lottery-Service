package com.lottery.lottery_service.account;

import static org.assertj.core.api.Assertions.assertThat;

import com.lottery.lottery_service.support.MutableClock;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class GuestIssueRateLimiterTest {

  private static final Instant T0 = Instant.parse("2026-11-06T10:00:00Z");

  private final MutableClock clock = new MutableClock(T0);
  private final GuestIssueRateLimiter limiter = new GuestIssueRateLimiter(clock);

  @Test
  @DisplayName("한 IP는 10분에 200회까지 받고 201번째는 창이 끝나는 시각을 돌려받는다(T40)")
  void limitPerWindow() {
    for (int i = 0; i < 200; i++) {
      clock.set(T0.plusSeconds(i));
      assertThat(limiter.tryAcquire("203.0.113.1")).isEmpty();
    }

    assertThat(limiter.tryAcquire("203.0.113.1")).contains(T0.plusSeconds(600));
    assertThat(limiter.tryAcquire("203.0.113.2")).isEmpty();
  }

  @Test
  @DisplayName("창이 끝나는 시각부터 다시 센다")
  void windowResets() {
    for (int i = 0; i < 201; i++) {
      limiter.tryAcquire("203.0.113.1");
    }
    clock.set(T0.plusSeconds(600).minusNanos(1));
    assertThat(limiter.tryAcquire("203.0.113.1")).isPresent();

    clock.set(T0.plusSeconds(600));
    assertThat(limiter.tryAcquire("203.0.113.1")).isEmpty();
  }
}
