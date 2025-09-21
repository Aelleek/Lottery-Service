package com.lottery.lottery_service.lotto.validation.rule;

import com.lottery.lottery_service.lotto.dto.LottoSet;
import java.util.List;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** 3구간(1–15 / 16–30 / 31–45) 중 한 구간이 6개(6–0–0)면 FAIL */
@Component
@Order(60)
public class ThreeZoneSuperExtremeRule implements LottoValidationRule {

  @Override
  public String id() {
    return "ThreeZoneSuperExtreme";
  }

  @Override
  public String reasonOnFail() {
    return "THREE_ZONE_6_0_0";
  }

  @Override
  public boolean enabled() {
    return true;
  }

  @Override
  public boolean validate(LottoSet set) {
    List<Integer> nums = set.getNumbers();
    if (nums == null || nums.size() != 6) return false;

    int z1 = 0, z2 = 0, z3 = 0;
    for (Integer n : nums) {
      if (n == null) return false;
      if (1 <= n && n <= 15) z1++;
      else if (16 <= n && n <= 30) z2++;
      else if (31 <= n && n <= 45) z3++;
      else return false; // 범위 밖
    }
    // 슈퍼 극단만 컷(6–0–0)
    return !(z1 == 6 || z2 == 6 || z3 == 6);
  }
}
