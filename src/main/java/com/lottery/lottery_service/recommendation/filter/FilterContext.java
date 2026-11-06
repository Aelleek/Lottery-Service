package com.lottery.lottery_service.recommendation.filter;

import com.lottery.lottery_service.common.number.LottoNumbers;
import java.util.Set;

/**
 * 생성 필터가 한 추천 요청에서 쓰는 자료.
 *
 * @param roundNo 추천 회차
 * @param pastFirstPrizes 추천 회차보다 앞선 회차의 게시된 1등 조합
 */
public record FilterContext(int roundNo, Set<LottoNumbers> pastFirstPrizes) {

  /** 1등 조합 집합은 바꿀 수 없는 사본으로 둔다. */
  public FilterContext {
    pastFirstPrizes = Set.copyOf(pastFirstPrizes);
  }
}
