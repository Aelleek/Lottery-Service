package com.lottery.lottery_service.account;

import com.lottery.lottery_service.common.web.IpRateLimiter;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * IP별 비회원 자격 발급 속도 제한. IP당 10분에 200회다(D-035). 인스턴스 1개 운영을 가정해 메모리에 둔다(03 1절).
 *
 * <p>10분 창은 그 IP의 첫 발급부터 센다. 창이 끝나면 새로 센다.
 */
@Component
public class GuestIssueRateLimiter {

  /** 한 창에서 허용하는 발급 수. */
  public static final int LIMIT = 200;

  /** 창 길이. */
  public static final Duration WINDOW = Duration.ofMinutes(10);

  private final IpRateLimiter limiter;

  /** 제한기를 만든다. */
  public GuestIssueRateLimiter(Clock clock) {
    this.limiter = new IpRateLimiter(LIMIT, WINDOW, clock);
  }

  /**
   * 발급 한 번을 센다.
   *
   * @return 허용하면 빈 값, 한도를 넘었으면 다시 시도할 수 있는 시각
   */
  public Optional<Instant> tryAcquire(String ip) {
    return limiter.tryAcquire(ip);
  }
}
