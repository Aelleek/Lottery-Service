package com.lottery.lottery_service.recommendation;

import com.lottery.lottery_service.common.number.LottoNumbers;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 저장할 추천 한 건.
 *
 * @param subjectId 주체 ID
 * @param roundNo 회차
 * @param sets 5세트(순서대로 1~5번 항목)
 * @param acceptedAt 회차 잠금 뒤 읽은 DB 시각
 * @param keyDigest {@code Idempotency-Key} 요약(32바이트)
 * @param requestHash 요청 본문 요약(32바이트)
 * @param policyVersion 이용 정책 버전
 * @param filterVersion 기본 추천의 필터 버전. 조건 추천이면 {@code null}
 * @param conditionJson 조건 추천의 조건 JSON. 기본 추천이면 {@code null}
 * @param responseExpiresAt 원본 응답 보관 기한
 */
public record NewRecommendation(
    long subjectId,
    int roundNo,
    List<LottoNumbers> sets,
    Instant acceptedAt,
    byte[] keyDigest,
    byte[] requestHash,
    long policyVersion,
    String filterVersion,
    String conditionJson,
    Instant responseExpiresAt) {

  /** 5세트가 서로 달라야 하고, 필터 버전과 조건 중 정확히 하나만 있어야 한다. */
  public NewRecommendation {
    sets = List.copyOf(sets);
    if (sets.size() != 5 || sets.stream().distinct().count() != 5) {
      throw new IllegalArgumentException("a recommendation has 5 distinct sets");
    }
    if ((filterVersion == null) == (conditionJson == null)) {
      throw new IllegalArgumentException("either filter version or condition is required");
    }
    Objects.requireNonNull(acceptedAt, "acceptedAt");
    Objects.requireNonNull(responseExpiresAt, "responseExpiresAt");
    if (keyDigest == null
        || keyDigest.length != 32
        || requestHash == null
        || requestHash.length != 32) {
      throw new IllegalArgumentException("digests must be 32 bytes");
    }
  }
}
