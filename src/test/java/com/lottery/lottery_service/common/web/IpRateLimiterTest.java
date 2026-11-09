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

  @Test
  @DisplayName("키가 많아도 정리는 1초에 한 번만 해서 요청마다 전체를 훑지 않는다")
  void purgesAtMostOncePerInterval() {
    Instant t0 = Instant.parse("2026-11-08T15:00:00Z");
    MutableClock clock = new MutableClock(t0);
    IpRateLimiter limiter = new IpRateLimiter(5, Duration.ofMinutes(1), clock);
    for (int i = 0; i <= IpRateLimiter.PURGE_THRESHOLD; i++) {
      limiter.tryAcquire("10.0." + i);
    }
    clock.set(t0.plusMillis(59_500));
    limiter.tryAcquire("x");

    clock.set(t0.plusMillis(60_200));
    limiter.tryAcquire("y");
    assertThat(limiter.trackedKeys()).isEqualTo(IpRateLimiter.PURGE_THRESHOLD + 3);

    clock.set(t0.plusMillis(60_600));
    limiter.tryAcquire("z");
    assertThat(limiter.trackedKeys()).isEqualTo(3);
  }
}
