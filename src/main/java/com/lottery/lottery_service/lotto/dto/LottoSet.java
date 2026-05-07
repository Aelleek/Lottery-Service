package com.lottery.lottery_service.lotto.dto;

import java.util.List;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class LottoSet {
  private List<Integer> numbers;

  public LottoSet(List<Integer> numbers) {
    validateNumbers(numbers);
    this.numbers = List.copyOf(numbers);
  }

  private void validateNumbers(List<Integer> numbers) {
    if (numbers == null) {
      throw new IllegalArgumentException("로또 번호 리스트는 null일 수 없습니다.");
    }

    if (numbers.size() != 6) {
      throw new IllegalArgumentException("로또 번호는 정확히 6개여야 합니다.");
    }
  }
}
