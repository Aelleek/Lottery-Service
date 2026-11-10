package com.lottery.lottery_service.recommendation;

import com.lottery.lottery_service.common.number.LottoNumbers;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 화면에 돌려줄 추천 결과.
 *
 * @param operationId 멱등 기록(명령) 외부 ID
 * @param publicId 추천 요청 외부 ID
 * @param roundNo 회차
 * @param sets 항목 순서대로 5세트
 * @param recordIds 항목 순서대로 연결한 기록 외부 ID. 기록이 지워졌으면 그 자리는 {@code null}
 * @param generationMode BASIC 또는 CONDITION
 * @param filterVersion 기본 추천에 쓴 생성 필터 버전. 조건 추천이면 {@code null}
 * @param responseExpiresAt 같은 요청에 이 결과를 다시 줄 수 있는 기한
 */
public record RecommendationView(
    UUID operationId,
    UUID publicId,
    int roundNo,
    List<LottoNumbers> sets,
    List<UUID> recordIds,
    String generationMode,
    String filterVersion,
    Instant responseExpiresAt) {}
