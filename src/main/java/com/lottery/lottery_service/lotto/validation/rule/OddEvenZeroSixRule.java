package com.lottery.lottery_service.lotto.validation.rule;

import com.lottery.lottery_service.lotto.dto.LottoSet;
import java.util.List;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** 짝/홀 분포가 0:6(또는 6:0)이면 FAIL */
@Component
@Order(40)
public class OddEvenZeroSixRule implements LottoValidationRule {

  @Override
  public String id() {
    return "OddEvenZeroSix";
  }

  @Override
  public String reasonOnFail() {
    return "ODD_EVEN_0_6";
  }

  @Override
  public boolean enabled() {
    return true;
  }

  @Override
  public boolean validate(LottoSet set) {
    List<Integer> nums = set.getNumbers();
    if (nums == null || nums.size() != 6) return false;

    int even = 0;
    for (Integer n : nums) {
      if (n == null) return false;
      if ((n % 2) == 0) even++;
    }
    int odd = 6 - even;
    // 극단 패턴만 컷
    return !(even == 0 || odd == 0);
  }
}
