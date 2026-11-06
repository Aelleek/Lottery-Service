package com.lottery.lottery_service.recommendation;

import com.lottery.lottery_service.common.number.LottoNumbers;
import java.util.List;
import java.util.UUID;

/**
 * 화면에 돌려줄 추천 결과.
 *
 * @param publicId 추천 요청 외부 ID
 * @param roundNo 회차
 * @param sets 항목 순서대로 5세트
 */
public record RecommendationView(UUID publicId, int roundNo, List<LottoNumbers> sets) {}
