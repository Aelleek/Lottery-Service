package com.lottery.lottery_service.recommendation;

import com.lottery.lottery_service.recommendation.generator.RecommendationCondition;

/**
 * 추천 요청 하나.
 *
 * @param subjectId 주체 ID
 * @param roundNo 명령 토큰에 묶인 회차
 * @param scheduleVersion 명령 토큰에 묶인 일정 버전
 * @param condition 조건. 기본 추천이면 {@link RecommendationCondition#none()}
 * @param keyDigest {@code Idempotency-Key} 요약(32바이트)
 * @param requestHash 요청 본문 요약(32바이트)
 */
public record RecommendCommand(
    long subjectId,
    int roundNo,
    long scheduleVersion,
    RecommendationCondition condition,
    byte[] keyDigest,
    byte[] requestHash) {}
