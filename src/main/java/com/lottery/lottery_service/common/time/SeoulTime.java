package com.lottery.lottery_service.common.time;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/** 서울(Asia/Seoul) 기준 날짜 경계 계산. 시각은 UTC {@link Instant}로 다루고 날짜를 나눌 때만 서울 시간을 쓴다. */
public final class SeoulTime {

  /** 서비스 기준 시간대. */
  public static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

  private SeoulTime() {}

  /** 주어진 시각의 서울 날짜. */
  public static LocalDate dateOf(Instant instant) {
    return instant.atZone(ZONE).toLocalDate();
  }

  /** 시계가 가리키는 현재 서울 날짜. */
  public static LocalDate today(Clock clock) {
    return dateOf(clock.instant());
  }

  /** 주어진 시각의 서울 기준 연월. */
  public static YearMonth monthOf(Instant instant) {
    return YearMonth.from(instant.atZone(ZONE));
  }

  /** 서울 날짜가 시작하는 시각(그 날 00:00 서울). */
  public static Instant startOfDay(LocalDate date) {
    return date.atStartOfDay(ZONE).toInstant();
  }

  /** 서울 날짜의 다음 날이 시작하는 시각. 하루 구간은 [startOfDay, endOfDayExclusive)다. */
  public static Instant endOfDayExclusive(LocalDate date) {
    return startOfDay(date.plusDays(1));
  }

  /** 주어진 시각을 서울 시간대로 표현한다. */
  public static ZonedDateTime atSeoul(Instant instant) {
    return instant.atZone(ZONE);
  }
}
