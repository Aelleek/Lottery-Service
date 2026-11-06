package com.lottery.lottery_service.recommendation.filter;

import com.lottery.lottery_service.common.number.LottoNumbers;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** 필터 2. 일의 자리 합이 14 미만이거나 42 초과면 거른다. */
@Component
@Order(2)
public class EndDigitSumFilter implements GenerationRule {

  @Override
  public String id() {
    return "END_DIGIT_SUM";
  }

  @Override
  public boolean accepts(LottoNumbers numbers, FilterContext context) {
    int sum = numbers.toList().stream().mapToInt(n -> n % 10).sum();
    return sum >= 14 && sum <= 42;
  }
}
