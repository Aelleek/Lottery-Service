package com.lottery.lottery_service.recommendation.filter;

import com.lottery.lottery_service.common.number.LottoNumbers;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** 필터 3. 일의 자리가 같은 숫자가 4개 이상이면 거른다. */
@Component
@Order(3)
public class SameEndDigitFilter implements GenerationRule {

  @Override
  public String id() {
    return "SAME_END_DIGIT";
  }

  @Override
  public boolean accepts(LottoNumbers numbers, FilterContext context) {
    int[] counts = new int[10];
    for (int n : numbers.toList()) {
      if (++counts[n % 10] >= 4) {
        return false;
      }
    }
    return true;
  }
}
