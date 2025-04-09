package com.lottery.lottery_service.lotto.dto;

import java.util.List;

public class LottoSet {
    private List<Integer> numbers;

    public LottoSet(List<Integer> numbers) {
        this.numbers = numbers;
    }

    public List<Integer> getNumbers() {
        return numbers;
    }

    public void setNumbers(List<Integer> numbers) {
        this.numbers = numbers;
    }

}
