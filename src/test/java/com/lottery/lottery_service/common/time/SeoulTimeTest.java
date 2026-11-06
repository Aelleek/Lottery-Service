package com.lottery.lottery_service.common.time;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SeoulTimeTest {

  @Test
  @DisplayName("UTC 14:59:59.999999는 서울 23:59:59.999999라 같은 날, UTC 15:00은 서울 다음 날 00:00이다")
  void dateBoundaryIsSeoulMidnight() {
    Instant lastMoment = Instant.parse("2026-11-07T14:59:59.999999Z");
    Instant nextDay = Instant.parse("2026-11-07T15:00:00Z");

    assertThat(SeoulTime.dateOf(lastMoment)).isEqualTo(LocalDate.of(2026, 11, 7));
    assertThat(SeoulTime.dateOf(nextDay)).isEqualTo(LocalDate.of(2026, 11, 8));
  }

  @Test
  @DisplayName("UTC로는 같은 날이어도 서울 기준으로는 날짜가 다를 수 있다")
  void sameUtcDateCanSpanTwoSeoulDates() {
    Instant utcMorning = Instant.parse("2026-11-07T00:30:00Z");
    Instant utcAfternoon = Instant.parse("2026-11-07T16:00:00Z");

    assertThat(SeoulTime.dateOf(utcMorning)).isEqualTo(LocalDate.of(2026, 11, 7));
    assertThat(SeoulTime.dateOf(utcAfternoon)).isEqualTo(LocalDate.of(2026, 11, 8));
  }

  @Test
  @DisplayName("하루 구간은 서울 00:00 시작을 포함하고 다음 날 00:00을 포함하지 않는다")
  void dayRangeIsHalfOpen() {
    LocalDate date = LocalDate.of(2026, 11, 8);

    assertThat(SeoulTime.startOfDay(date)).isEqualTo(Instant.parse("2026-11-07T15:00:00Z"));
    assertThat(SeoulTime.endOfDayExclusive(date)).isEqualTo(Instant.parse("2026-11-08T15:00:00Z"));
    assertThat(SeoulTime.dateOf(SeoulTime.endOfDayExclusive(date))).isEqualTo(date.plusDays(1));
  }

  @Test
  @DisplayName("월 경계도 서울 기준이다: UTC 10월 31일 15:00은 서울 11월이다")
  void monthBoundaryIsSeoul() {
    assertThat(SeoulTime.monthOf(Instant.parse("2026-10-31T14:59:59Z")))
        .isEqualTo(YearMonth.of(2026, 10));
    assertThat(SeoulTime.monthOf(Instant.parse("2026-10-31T15:00:00Z")))
        .isEqualTo(YearMonth.of(2026, 11));
  }

  @Test
  @DisplayName("today는 주입한 시계의 시간대와 상관없이 서울 날짜를 준다")
  void todayIgnoresClockZone() {
    Clock utcClock = Clock.fixed(Instant.parse("2026-12-31T15:00:00Z"), ZoneOffset.UTC);

    assertThat(SeoulTime.today(utcClock)).isEqualTo(LocalDate.of(2027, 1, 1));
  }

  @Test
  @DisplayName("기본 시계 빈은 UTC다")
  void defaultClockIsUtc() {
    assertThat(new ClockConfig().clock().getZone()).isEqualTo(ZoneOffset.UTC);
  }
}
