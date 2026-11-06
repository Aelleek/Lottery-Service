package com.lottery.lottery_service.common.job;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** 테스트에서 시각을 옮길 수 있는 UTC 시계. */
final class MutableClock extends Clock {

  private Instant now;

  MutableClock(Instant now) {
    this.now = now;
  }

  void set(Instant now) {
    this.now = now;
  }

  @Override
  public ZoneId getZone() {
    return ZoneOffset.UTC;
  }

  @Override
  public Clock withZone(ZoneId zone) {
    return this;
  }

  @Override
  public Instant instant() {
    return now;
  }
}
