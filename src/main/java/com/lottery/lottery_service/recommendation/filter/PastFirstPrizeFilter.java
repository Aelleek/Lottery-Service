package com.lottery.lottery_service.recommendation.filter;

import com.lottery.lottery_service.common.number.LottoNumbers;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** 필터 1. 추천 회차보다 앞선 회차의 게시된 1등 조합과 같으면 거른다. 보너스는 비교하지 않는다. */
@Component
@Order(1)
public class PastFirstPrizeFilter implements GenerationRule {

  @Override
  public String id() {
    return "PAST_FIRST_PRIZE";
  }

  @Override
  public boolean accepts(LottoNumbers numbers, FilterContext context) {
    return !context.pastFirstPrizes().contains(numbers);
  }
}
