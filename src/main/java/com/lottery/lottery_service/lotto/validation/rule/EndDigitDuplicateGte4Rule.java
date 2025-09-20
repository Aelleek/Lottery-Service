package com.lottery.lottery_service.lotto.validation.rule;


import com.lottery.lottery_service.lotto.dto.LottoSet;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * [룰] 끝자리(1의 자리) 중 같은 값이 4개 이상 나오면 FAIL.
 * - 보너스 번호는 미포함.
 */
@Component
@Order(20) // 합 룰 다음에 가볍게 확인
public class EndDigitDuplicateGte4Rule implements LottoValidationRule {

    private static final int THRESHOLD = 4;

    @Override
    public String id() {
        return "EndDigitDupGte4";
    }

    @Override
    public String reasonOnFail() {
        return "END_DIGIT_DUP_GE_4";
    }

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public boolean validate(LottoSet set) {
        List<Integer> nums = set.getNumbers();
        if (nums == null || nums.size() != 6) return false;

        Map<Integer, Integer> cnt = new HashMap<>();
        for (Integer n : nums) {
            if (n == null) return false;
            int d = n % 10;
            cnt.put(d, cnt.getOrDefault(d, 0) + 1);
            if (cnt.get(d) >= THRESHOLD) return false; // FAIL
        }
        return true; // PASS
    }
}