package com.lottery.lottery_service.result.source;

import com.lottery.lottery_service.common.time.SeoulTime;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;

/**
 * 결과 수집 시도 시각(07 1절). 추첨 10분 뒤부터 5분 간격으로, 추첨 다음 날 서울 12:00 이후로는 1시간 간격으로 게시될 때까지 시도한다. 주기는 초기
 * 설정값이다.
 */
public final class CollectionSchedule {

  /** 첫 시도는 추첨 뒤 이만큼 지나서. */
  public static final Duration FIRST_DELAY = Duration.ofMinutes(10);

  /** 다음 날 12:00 전 간격. */
  public static final Duration SHORT_INTERVAL = Duration.ofMinutes(5);

  /** 다음 날 12:00 이후 간격. */
  public static final Duration LONG_INTERVAL = Duration.ofHours(1);

  private static final LocalTime SLOW_DOWN_AT = LocalTime.NOON;

  private CollectionSchedule() {}

  /**
   * 다음 시도 시각.
   *
   * @param drawAt 추첨 시각
   * @param lastAttemptAt 마지막 시도 시각. 아직 없으면 {@code null}
   */
  public static Instant nextAttempt(Instant drawAt, Instant lastAttemptAt) {
    Instant first = drawAt.plus(FIRST_DELAY);
    if (lastAttemptAt == null || lastAttemptAt.isBefore(first)) {
      return first;
    }
    Instant slowDown =
        SeoulTime.atSeoul(drawAt)
            .toLocalDate()
            .plusDays(1)
            .atTime(SLOW_DOWN_AT)
            .atZone(SeoulTime.ZONE)
            .toInstant();
    Instant next =
        lastAttemptAt.plus(lastAttemptAt.isBefore(slowDown) ? SHORT_INTERVAL : LONG_INTERVAL);
    if (lastAttemptAt.isBefore(slowDown) && next.isAfter(slowDown)) {
      return slowDown;
    }
    return next;
  }
}
