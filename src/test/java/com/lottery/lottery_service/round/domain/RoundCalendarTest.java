package com.lottery.lottery_service.round.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lottery.lottery_service.common.error.BusinessException;
import com.lottery.lottery_service.common.error.ErrorCode;
import com.lottery.lottery_service.common.time.SeoulTime;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RoundCalendarTest {

  private static final RoundSchedule R1200 =
      new RoundSchedule(
          1200, seoul("2026-11-01T06:00"), seoul("2026-11-07T20:00"), seoul("2026-11-07T20:35"), 1);
  private static final RoundSchedule R1201 =
      new RoundSchedule(
          1201, seoul("2026-11-08T06:00"), seoul("2026-11-14T20:00"), seoul("2026-11-14T20:35"), 3);

  private final RoundCalendar calendar = new RoundCalendar(List.of(R1201, R1200));

  @Test
  @DisplayName("마감 1마이크로초 전까지는 이번 회차 추천을 받는다")
  void openUntilJustBeforeClose() {
    RoundContext ctx = calendar.contextAt(R1200.salesCloseAt().minusNanos(1_000));

    assertThat(ctx).isEqualTo(new RoundContext(1200, 1, true, null));
  }

  @Test
  @DisplayName("마감 시각부터는 새 추천만 막고, 이번 회차는 방금 마감한 회차이며 다음 개시 시각을 알려 준다")
  void closedFromCloseInstant() {
    RoundContext ctx = calendar.contextAt(R1200.salesCloseAt());

    assertThat(ctx).isEqualTo(new RoundContext(1200, 1, false, R1201.salesOpenAt()));
  }

  @Test
  @DisplayName("추첨이 지나도 다음 개시 전이면 마감 구간이다(결과 수집과 무관)")
  void afterDrawStillClosedUntilNextOpen() {
    RoundContext ctx = calendar.contextAt(R1201.salesOpenAt().minusNanos(1_000));

    assertThat(ctx.currentRound()).isEqualTo(1200);
    assertThat(ctx.recommendationOpen()).isFalse();
  }

  @Test
  @DisplayName("다음 회차 개시 시각에 추천과 이번 회차가 함께 바뀐다")
  void nextRoundOpensAtOpenInstant() {
    RoundContext ctx = calendar.contextAt(R1201.salesOpenAt());

    assertThat(ctx).isEqualTo(new RoundContext(1201, 3, true, null));
  }

  @Test
  @DisplayName("평일 심야처럼 판매 시간 밖이어도 같은 회차 안이면 추천을 막지 않는다")
  void weekdayNightIsOpen() {
    assertThat(calendar.contextAt(seoul("2026-11-04T03:00")).recommendationOpen()).isTrue();
  }

  @Test
  @DisplayName("다음 회차 일정이 없으면 계산으로 만들지 않고 추천만 멈춘다")
  void missingNextScheduleStopsRecommendationOnly() {
    RoundCalendar onlyCurrent = new RoundCalendar(List.of(R1200));

    RoundContext ctx = onlyCurrent.contextAt(seoul("2026-11-09T12:00"));

    assertThat(ctx).isEqualTo(new RoundContext(1200, 1, false, null));
  }

  @Test
  @DisplayName("개시한 회차가 없으면 SCHEDULE_UNAVAILABLE이다")
  void noOpenedRoundIsUnavailable() {
    assertThatThrownBy(() -> calendar.contextAt(R1200.salesOpenAt().minusSeconds(1)))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getCode())
        .isEqualTo(ErrorCode.SCHEDULE_UNAVAILABLE);
    assertThatThrownBy(() -> new RoundCalendar(List.of()).contextAt(Instant.EPOCH))
        .isInstanceOf(BusinessException.class);
  }

  @Test
  @DisplayName("회차별 예외 일정은 등록된 값을 그대로 쓴다")
  void exceptionalScheduleUsesRegisteredValues() {
    RoundSchedule holiday =
        new RoundSchedule(
            1202,
            seoul("2026-11-15T06:00"),
            seoul("2026-11-20T18:00"),
            seoul("2026-11-21T20:35"),
            1);
    RoundCalendar withHoliday = new RoundCalendar(List.of(R1201, holiday));

    assertThat(withHoliday.contextAt(seoul("2026-11-20T17:59")).recommendationOpen()).isTrue();
    assertThat(withHoliday.contextAt(seoul("2026-11-20T18:00")).recommendationOpen()).isFalse();
  }

  @Test
  @DisplayName("T06: 마감 전에 계산을 시작했어도 저장 시각이 마감이면 ROUND_CHANGED로 거부한다")
  void saveAfterCloseRejected() {
    Instant started = R1200.salesCloseAt().minusSeconds(2);
    assertThat(calendar.contextAt(started).recommendationOpen()).isTrue();

    assertThatThrownBy(() -> calendar.requireRecommendationAccepted(1200, 1, R1200.salesCloseAt()))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getCode())
        .isEqualTo(ErrorCode.ROUND_CHANGED);
  }

  @Test
  @DisplayName("일정 버전이 요청 때와 다르거나 없는 회차면 ROUND_CHANGED다")
  void versionMismatchOrUnknownRoundRejected() {
    Instant inside = R1201.salesOpenAt().plusSeconds(60);

    assertThatCode(() -> calendar.requireRecommendationAccepted(1201, 3, inside))
        .doesNotThrowAnyException();
    assertThatThrownBy(() -> calendar.requireRecommendationAccepted(1201, 2, inside))
        .isInstanceOf(BusinessException.class);
    assertThatThrownBy(() -> calendar.requireRecommendationAccepted(1300, 1, inside))
        .isInstanceOf(BusinessException.class);
    assertThatThrownBy(
            () ->
                calendar.requireRecommendationAccepted(1201, 3, R1201.salesOpenAt().minusNanos(1)))
        .isInstanceOf(BusinessException.class);
  }

  @Test
  @DisplayName("같은 회차가 두 번이거나 개시가 마감보다 늦으면 거부한다")
  void rejectsInvalidSchedules() {
    assertThatThrownBy(() -> new RoundCalendar(List.of(R1200, R1200)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new RoundSchedule(1, R1200.salesCloseAt(), R1200.salesCloseAt(), R1200.drawAt(), 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new RoundSchedule(0, R1200.salesOpenAt(), R1200.salesCloseAt(), R1200.drawAt(), 1))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static Instant seoul(String localDateTime) {
    return LocalDateTime.parse(localDateTime).atZone(SeoulTime.ZONE).toInstant();
  }
}
