package com.lottery.lottery_service.recommendation;

import java.util.List;
import java.util.UUID;

/**
 * 저장한 추천.
 *
 * @param requestId 추천 요청 ID
 * @param publicId 추천 요청 외부 ID
 * @param recordIds 항목 순서대로 연결한 기록 ID
 */
public record SavedRecommendation(long requestId, UUID publicId, List<Long> recordIds) {}
