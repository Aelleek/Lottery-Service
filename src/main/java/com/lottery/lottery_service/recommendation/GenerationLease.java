package com.lottery.lottery_service.recommendation;

import java.time.Instant;

/**
 * 주체의 추천 생성 권한. 저장할 때 {@link #fence()}가 그대로인지 확인해 늦게 끝난 생성을 거부한다.
 *
 * @param subjectId 주체 ID
 * @param fence 이 생성의 번호
 * @param leaseUntil 생성 권한 만료 시각
 */
public record GenerationLease(long subjectId, long fence, Instant leaseUntil) {}
