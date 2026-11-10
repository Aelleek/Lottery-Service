package com.lottery.lottery_service.recommendation.filter;

import com.lottery.lottery_service.common.number.LottoNumbers;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** 필터 4. 1~22를 저, 23~45를 고로 볼 때 6개가 모두 저이거나 모두 고면 거른다. */
@Component
@Order(4)
public class LowHighFilter implements GenerationRule {

  @Override
  public String id() {
    return "LOW_HIGH";
  }

  @Override
  public boolean accepts(LottoNumbers numbers, FilterContext context) {
    long low = numbers.toList().stream().filter(n -> n <= 22).count();
    return low != 0 && low != LottoNumbers.SIZE;
  }
}
