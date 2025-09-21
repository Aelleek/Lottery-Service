package com.lottery.lottery_service.lotto.validation.rule;

import com.lottery.lottery_service.lotto.dto.LottoSet;
import java.util.List;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** [룰] 끝자리 합이 14~42(포함) 안이면 PASS, 그 외 FAIL. - 보너스 번호는 미포함 (6개 본 숫자만). */
@Component
@Order(10) // 가볍고 빠른 체크이므로 앞쪽에 배치
public class EndDigitSumRule implements LottoValidationRule {

  private static final int MIN = 14;
  private static final int MAX = 42;

  @Override
  public String id() {
    return "EndDigitSum";
  }

  @Override
  public String reasonOnFail() {
    return "END_SUM_OUT_OF_RANGE"; // 14~42 밖
  }

  @Override
  public boolean enabled() {
    return true; // 토글 필요하면 환경변수 연동 가능
  }

  @Override
  public boolean validate(LottoSet set) {
    List<Integer> nums = set.getNumbers();
    if (nums == null || nums.size() != 6) return false;

    int sum = 0;
    for (Integer n : nums) {
      if (n == null) return false;
      sum += (n % 10);
    }
    return (sum >= MIN && sum <= MAX);
  }
}
