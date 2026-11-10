package com.lottery.lottery_service.common.job;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * job 처리 설정.
 *
 * @param maxAttempts 실패와 회수를 합친 시도 상한. 이 횟수에 닿으면 DEAD
 * @param leaseDuration worker가 job을 가져갈 때 받는 lease 길이
 */
@ConfigurationProperties(prefix = "lottery.job")
public record JobProperties(Integer maxAttempts, Duration leaseDuration) {

  /** 설정이 없을 때의 기본값(시도 5회, lease 5분)을 채운다. */
  public JobProperties {
    if (maxAttempts == null) {
      maxAttempts = 5;
    }
    if (leaseDuration == null) {
      leaseDuration = Duration.ofMinutes(5);
    }
    if (maxAttempts < 1 || leaseDuration.isNegative() || leaseDuration.isZero()) {
      throw new IllegalArgumentException("invalid job properties");
    }
  }
}
