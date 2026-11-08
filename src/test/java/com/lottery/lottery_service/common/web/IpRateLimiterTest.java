package com.lottery.lottery_service.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.lottery.lottery_service.support.MutableClock;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class IpRateLimiterTest {

  @Test
  @DisplayName("기억하는 키가 한도를 넘으면 창이 끝난 키를 지우고, 창이 남은 키는 남긴다")
  void purgesExpiredWindows() {
    MutableClock clock = new MutableClock(Instant.parse("2026-11-08T15:00:00Z"));
    IpRateLimiter limiter = new IpRateLimiter(5, Duration.ofMinutes(1), clock);
    for (int i = 0; i <= IpRateLimiter.PURGE_THRESHOLD; i++) {
      limiter.tryAcquire("10.0." + i);
    }
    clock.set(clock.instant().plusSeconds(30));
    limiter.tryAcquire("fresh");
    assertThat(limiter.trackedKeys()).isEqualTo(IpRateLimiter.PURGE_THRESHOLD + 2);

    clock.set(clock.instant().plusSeconds(31));
    limiter.tryAcquire("later");
    assertThat(limiter.trackedKeys()).isEqualTo(2);
  }
}
