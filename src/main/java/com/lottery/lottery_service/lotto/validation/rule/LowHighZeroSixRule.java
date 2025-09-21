package com.lottery.lottery_service.lotto.validation.rule;

import com.lottery.lottery_service.lotto.dto.LottoSet;
import java.util.List;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** 1~22=저, 23~45=고. 분포가 0:6(또는 6:0)이면 FAIL */
@Component
@Order(30)
public class LowHighZeroSixRule implements LottoValidationRule {

  @Override
  public String id() {
    return "LowHighZeroSix";
  }

  @Override
  public String reasonOnFail() {
    return "LOW_HIGH_0_6";
  }

  @Override
  public boolean enabled() {
    return true;
  }

  @Override
  public boolean validate(LottoSet set) {
    List<Integer> nums = set.getNumbers();
    if (nums == null || nums.size() != 6) return false;

    int low = 0; // 1..22
    for (Integer n : nums) {
      if (n == null) return false;
      if (1 <= n && n <= 22) low++;
    }
    int high = 6 - low; // 23..45
    // 극단 패턴만 컷
    return !(low == 0 || high == 0);
  }
}
