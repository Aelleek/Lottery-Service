package com.lottery.lottery_service.lotto.validation.rule;

import com.lottery.lottery_service.lotto.dto.LottoSet;
import java.util.*;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * AC(산술적 복잡도) <= 6 이면 FAIL. - 정의: 정렬된 6개 수의 모든 (aj-ai) 차이값의 서로 다른 개수. - 최근 데이터 기준 AC≤6은 드물지만 안전 장치로
 * 하드컷.
 */
@Component
@Order(50)
public class ArithmeticComplexityMax6Rule implements LottoValidationRule {

  @Override
  public String id() {
    return "ArithmeticComplexityMax6";
  }

  @Override
  public String reasonOnFail() {
    return "AC_LE_6";
  }

  @Override
  public boolean enabled() {
    return true;
  }

  @Override
  public boolean validate(LottoSet set) {
    List<Integer> nums = set.getNumbers();
    if (nums == null || nums.size() != 6) return false;

    nums.sort(Integer::compareTo);
    Set<Integer> diffs = new HashSet<>();
    for (int i = 0; i < 6; i++) {
      Integer ai = nums.get(i);
      if (ai == null) return false;
      for (int j = i + 1; j < 6; j++) {
        Integer aj = nums.get(j);
        if (aj == null) return false;
        diffs.add(aj - ai);
      }
    }
    int ac = diffs.size();
    return ac > 6; // AC≤6이면 FAIL
  }
}
