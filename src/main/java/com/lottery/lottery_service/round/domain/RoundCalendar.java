package com.lottery.lottery_service.round.domain;

import com.lottery.lottery_service.common.error.BusinessException;
import com.lottery.lottery_service.common.error.ErrorCode;
import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/** 등록된 회차 일정으로 이번 회차와 추천 가능 여부를 정한다. 일정은 계산으로 만들지 않고 등록된 것만 쓴다. 결과 수집 상태와는 상관없다. */
public final class RoundCalendar {

  private final TreeMap<Integer, RoundSchedule> byRound = new TreeMap<>();

  /** 등록된 일정으로 만든다. 같은 회차가 두 번 있으면 거부한다. */
  public RoundCalendar(Collection<RoundSchedule> schedules) {
    for (RoundSchedule s : schedules) {
      if (byRound.putIfAbsent(s.roundNo(), s) != null) {
        throw new IllegalArgumentException("같은 회차 일정이 두 번 있습니다: " + s.roundNo());
      }
    }
  }

  /**
   * 주어진 시각의 회차 상태. 판매를 개시한 회차 중 가장 늦게 개시한 회차가 이번 회차다.
   *
   * @throws BusinessException 개시한 회차가 하나도 없으면 {@code SCHEDULE_UNAVAILABLE}
   */
  public RoundContext contextAt(Instant now) {
    RoundSchedule current =
        currentAt(now)
            .orElseThrow(
                () -> new BusinessException(ErrorCode.SCHEDULE_UNAVAILABLE, "회차 일정을 확인할 수 없습니다."));
    if (current.acceptsRecommendationAt(now)) {
      return new RoundContext(current.roundNo(), current.scheduleVersion(), true, null);
    }
    RoundSchedule next = byRound.get(current.roundNo() + 1);
    Instant nextOpenAt = next == null ? null : next.salesOpenAt();
    return new RoundContext(current.roundNo(), current.scheduleVersion(), false, nextOpenAt);
  }

  /**
   * 추천을 저장해도 되는지 확인한다. 저장 시각({@code acceptedAt})이 그 회차 판매 구간 안이고 일정 버전이 요청 때와 같아야 한다.
   *
   * @throws BusinessException 회차가 없거나, 마감이 지났거나, 일정 버전이 바뀌면 {@code ROUND_CHANGED}
   */
  public void requireRecommendationAccepted(int roundNo, long scheduleVersion, Instant acceptedAt) {
    RoundSchedule schedule = byRound.get(roundNo);
    if (schedule == null
        || schedule.scheduleVersion() != scheduleVersion
        || !schedule.acceptsRecommendationAt(acceptedAt)) {
      throw new BusinessException(ErrorCode.ROUND_CHANGED, "회차 정보를 새로 확인해 주세요.");
    }
  }

  private Optional<RoundSchedule> currentAt(Instant now) {
    return byRound.values().stream()
        .filter(s -> !s.salesOpenAt().isAfter(now))
        .max(Comparator.comparing(RoundSchedule::salesOpenAt));
  }

  /** 등록된 일정(회차 순). */
  public Map<Integer, RoundSchedule> schedules() {
    return Collections.unmodifiableMap(byRound);
  }
}
