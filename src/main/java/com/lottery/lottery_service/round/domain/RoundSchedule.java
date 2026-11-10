package com.lottery.lottery_service.round.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * 한 회차의 판매 일정. 판매 개시부터 마감 직전까지 새 추천을 받는다. 시각은 UTC {@link Instant}다.
 *
 * @param roundNo 회차 번호(1 이상)
 * @param salesOpenAt 판매 개시 시각(포함)
 * @param salesCloseAt 판매 마감 시각(이 시각부터 새 추천을 받지 않는다)
 * @param drawAt 추첨 시각
 * @param scheduleVersion 일정 버전. 일정이 바뀌면 오른다
 */
public record RoundSchedule(
    int roundNo, Instant salesOpenAt, Instant salesCloseAt, Instant drawAt, long scheduleVersion) {

  /** 회차 번호가 1 이상이고 개시가 마감보다 앞서야 한다. */
  public RoundSchedule {
    Objects.requireNonNull(salesOpenAt, "salesOpenAt");
    Objects.requireNonNull(salesCloseAt, "salesCloseAt");
    Objects.requireNonNull(drawAt, "drawAt");
    if (roundNo <= 0) {
      throw new IllegalArgumentException("회차 번호는 1 이상이어야 합니다: " + roundNo);
    }
    if (!salesOpenAt.isBefore(salesCloseAt)) {
      throw new IllegalArgumentException("판매 개시는 마감보다 앞서야 합니다. 회차 " + roundNo);
    }
  }

  /** 주어진 시각에 새 추천을 받는지. 개시 시각은 포함하고 마감 시각은 포함하지 않는다. */
  public boolean acceptsRecommendationAt(Instant at) {
    return !at.isBefore(salesOpenAt) && at.isBefore(salesCloseAt);
  }
}
