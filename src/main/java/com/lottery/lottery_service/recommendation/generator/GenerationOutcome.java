package com.lottery.lottery_service.recommendation.generator;

import com.lottery.lottery_service.common.number.LottoNumbers;
import java.util.List;

/** 추천 생성 결과. 성공, 후보 부족, 계산 지연을 구분한다(05 2절 5번). */
public sealed interface GenerationOutcome {

  /** 서로 다르고 이력에 없는 5세트를 만들었다. */
  record Generated(List<LottoNumbers> sets) implements GenerationOutcome {

    /** 정확히 5세트여야 한다. */
    public Generated {
      sets = List.copyOf(sets);
      if (sets.size() != RecommendationGenerator.SETS) {
        throw new IllegalArgumentException("a recommendation has exactly 5 sets");
      }
    }
  }

  /** 조건과 이력으로 남은 후보가 5개 미만임을 전부 확인했다. */
  record Exhausted(long remaining) implements GenerationOutcome {}

  /** 시도 횟수나 시간 안에 5세트를 채우지 못했다. 후보가 부족한지는 모른다. */
  record TimedOut() implements GenerationOutcome {}
}
