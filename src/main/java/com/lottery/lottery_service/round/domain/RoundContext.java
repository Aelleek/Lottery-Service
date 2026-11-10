package com.lottery.lottery_service.round.domain;

import java.time.Instant;

/**
 * 어떤 시각의 회차 상태. openapi {@code Context}의 회차 부분이다.
 *
 * @param currentRound 이번 회차. 판매 중인 회차이고, 마감 구간에는 방금 마감한 회차다
 * @param scheduleVersion 이번 회차의 일정 버전
 * @param recommendationOpen 새 추천을 받는지
 * @param nextOpenAt 마감 구간이면 다음 회차 개시 시각. 판매 중이거나 다음 일정이 없으면 {@code null}
 */
public record RoundContext(
    int currentRound, long scheduleVersion, boolean recommendationOpen, Instant nextOpenAt) {}
