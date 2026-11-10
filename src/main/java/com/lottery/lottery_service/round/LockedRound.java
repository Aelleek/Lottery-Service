package com.lottery.lottery_service.round;

import com.lottery.lottery_service.round.domain.RoundSchedule;
import java.time.Instant;

/**
 * 공유 잠금을 잡은 회차와 잠금을 잡은 뒤 읽은 DB 시각.
 *
 * @param schedule 회차 일정
 * @param sealed 마감 봉인 여부
 * @param cutoffCloseAt 봉인 때 고정한 판매 마감 시각. 봉인 전이면 {@code null}
 * @param dbNow 잠금을 잡은 뒤 읽은 DB 시각(UTC)
 */
public record LockedRound(
    RoundSchedule schedule, boolean sealed, Instant cutoffCloseAt, Instant dbNow) {}
