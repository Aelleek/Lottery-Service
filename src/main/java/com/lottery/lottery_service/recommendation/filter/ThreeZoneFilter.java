package com.lottery.lottery_service.recommendation.filter;

import com.lottery.lottery_service.common.number.LottoNumbers;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** 필터 7. 1~15, 16~30, 31~45 중 한 구간에 6개가 모두 있으면 거른다. */
@Component
@Order(7)
public class ThreeZoneFilter implements GenerationRule {

  @Override
  public String id() {
    return "THREE_ZONE";
  }

  @Override
  public boolean accepts(LottoNumbers numbers, FilterContext context) {
    int[] zones = new int[3];
    for (int n : numbers.toList()) {
      if (++zones[(n - 1) / 15] == LottoNumbers.SIZE) {
        return false;
      }
    }
    return true;
  }
}
