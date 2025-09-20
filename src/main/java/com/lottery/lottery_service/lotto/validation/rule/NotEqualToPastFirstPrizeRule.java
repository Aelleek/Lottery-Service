package com.lottery.lottery_service.lotto.validation.rule;

import com.lottery.lottery_service.lotto.dto.LottoSet;
import com.lottery.lottery_service.lotto.validation.cache.LottoWinnerDataCache;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * [룰 구현체] 과거 "1등 당첨 조합"과 "완전히 동일"한 조합을 불허.
 *
 * 판정 로직:
 * 1) LottoSet의 숫자 6개를 복사 후 오름차순 정렬.
 * 2) 공백(" ")으로 조인하여 정규화 문자열 생성(예: "1 3 12 25 34 41").
 * 3) LottoWinnerDataCache.contains(normalized) == true 이면 FAIL(false), 아니면 PASS(true).
 *
 * 비교 범위:
 * - 보너스 번호는 비교 대상이 아님(과거 1등 6개만 비교).
 *
 * 주의:
 * - 정규화 포맷은 LottoWinnerData.winnerData와 "정확히 동일"해야 매칭된다.
 */
@Component
@Order(100)
public class NotEqualToPastFirstPrizeRule implements LottoValidationRule {

    private static final String REASON = "EQUALS_PAST_FIRST_PRIZE";
    private final LottoWinnerDataCache cache;

    public NotEqualToPastFirstPrizeRule(LottoWinnerDataCache cache) {
        this.cache = cache;
    }

    @Override
    public String id() {
        return "NOT_EQUAL_TO_PAST_FIRST_PRIZE";
    }

    @Override
    public String reasonOnFail() {
        return REASON;
    }

    @Override
    public boolean validate(LottoSet set) {
        // 1) 입력 방어: null / 사이즈 체크
        List<Integer> nums = set.getNumbers(); // List<Integer>
        if (nums == null || nums.size() != 6) {
            throw new IllegalArgumentException("LottoSet must contain exactly 6 numbers");
        }

        // 2) 방어적 복사 후 오름차순 정렬 (원본 훼손 금지)
        List<Integer> copy = new ArrayList<>(nums);
        Collections.sort(copy);

        // 3) 정규화 문자열 생성: "1 3 12 25 34 41"
        String normalized = copy.stream()
                .map(String::valueOf)
                .collect(Collectors.joining(" "));

        // 4) 캐시에 존재하면 과거 1등과 완전 동일 → FAIL(false), 아니면 PASS(true)
        return !cache.contains(normalized);
    }
}