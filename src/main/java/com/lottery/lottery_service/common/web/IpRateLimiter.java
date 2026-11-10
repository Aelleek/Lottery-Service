package com.lottery.lottery_service.common.web;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** 키(IP 등)별 고정 창 속도 제한. 창은 그 키의 첫 요청부터 센다. 인스턴스 1개 운영을 가정해 메모리에 둔다(03 6.5). */
public class IpRateLimiter {

  /** 기억하는 키가 이 수를 넘으면 창이 끝난 키를 지운다. */
  static final int PURGE_THRESHOLD = 10_000;

  private final int limit;
  private final Duration window;
  private final Clock clock;
  private final Map<String, Window> windows = new ConcurrentHashMap<>();

  /** 창마다 {@code limit}회를 허용하는 제한기를 만든다. */
  public IpRateLimiter(int limit, Duration window, Clock clock) {
    this.limit = limit;
    this.window = window;
    this.clock = clock;
  }

  /**
   * 요청 한 번을 센다.
   *
   * @return 허용하면 빈 값, 한도를 넘었으면 다시 시도할 수 있는 시각
   */
  public Optional<Instant> tryAcquire(String key) {
    Instant now = clock.instant();
    if (windows.size() > PURGE_THRESHOLD) {
      windows.values().removeIf(w -> !now.isBefore(w.end()));
    }
    Window next =
        windows.compute(
            key,
            (k, current) ->
                current == null || !now.isBefore(current.end())
                    ? new Window(now.plus(window), 1)
                    : new Window(current.end(), current.count() + 1));
    return next.count() <= limit ? Optional.empty() : Optional.of(next.end());
  }

  /** 지금 기억하는 키 수. 정리가 도는지 확인할 때 쓴다. */
  public int trackedKeys() {
    return windows.size();
  }

  private record Window(Instant end, int count) {}
}
