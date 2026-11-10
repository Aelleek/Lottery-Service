package com.lottery.lottery_service.recommendation.filter;

import com.lottery.lottery_service.common.number.LottoNumbers;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** 필터 5. 6개가 모두 홀수이거나 모두 짝수면 거른다. */
@Component
@Order(5)
public class OddEvenFilter implements GenerationRule {

  @Override
  public String id() {
    return "ODD_EVEN";
  }

  @Override
  public boolean accepts(LottoNumbers numbers, FilterContext context) {
    long even = numbers.toList().stream().filter(n -> n % 2 == 0).count();
    return even != 0 && even != LottoNumbers.SIZE;
  }
}
