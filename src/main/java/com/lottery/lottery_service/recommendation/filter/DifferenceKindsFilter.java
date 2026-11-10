package com.lottery.lottery_service.recommendation.filter;

import com.lottery.lottery_service.common.number.LottoNumbers;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** 필터 6. 두 숫자씩 짝지은 15개 차이 중 서로 다른 값이 6개 이하면 거른다(D-033). */
@Component
@Order(6)
public class DifferenceKindsFilter implements GenerationRule {

  @Override
  public String id() {
    return "DIFFERENCE_KINDS";
  }

  @Override
  public boolean accepts(LottoNumbers numbers, FilterContext context) {
    List<Integer> values = numbers.toList();
    Set<Integer> differences = new HashSet<>();
    for (int i = 0; i < values.size(); i++) {
      for (int j = i + 1; j < values.size(); j++) {
        differences.add(Math.abs(values.get(j) - values.get(i)));
      }
    }
    return differences.size() > 6;
  }
}
