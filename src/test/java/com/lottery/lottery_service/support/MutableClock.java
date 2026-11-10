package com.lottery.lottery_service.support;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** 테스트에서 시각을 옮길 수 있는 UTC 시계. */
public final class MutableClock extends Clock {

  private Instant now;

  /** 주어진 시각에서 시작한다. */
  public MutableClock(Instant now) {
    this.now = now;
  }

  /** 시각을 옮긴다. */
  public void set(Instant now) {
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
