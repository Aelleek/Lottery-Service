package com.lottery.lottery_service.result.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lottery.lottery_service.common.number.InvalidLottoNumberException;
import com.lottery.lottery_service.common.number.LottoNumber;
import com.lottery.lottery_service.common.number.LottoNumbers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class WinningNumbersTest {

  private final WinningNumbers winning =
      new WinningNumbers(LottoNumbers.of(1, 2, 3, 4, 5, 6), LottoNumber.of(7));

  @ParameterizedTest(name = "{0} -> {1}")
  @CsvSource({
    "'1,2,3,4,5,6', FIRST",
    "'6,5,4,3,2,1', FIRST",
    "'1,2,3,4,5,7', SECOND",
    "'1,2,3,4,5,45', THIRD",
    "'1,2,3,4,44,45', FOURTH",
    "'1,2,3,43,44,45', FIFTH",
    "'1,2,42,43,44,45', NONE",
    "'1,41,42,43,44,45', NONE",
    "'40,41,42,43,44,45', NONE"
  })
  @DisplayName("T23: 1등 6개, 2등 5개+보너스, 3등 5개, 4등 4개, 5등 3개")
  void ranks(String ticket, PrizeRank expected) {
    assertThat(winning.rankOf(parse(ticket))).isEqualTo(expected);
  }

  @ParameterizedTest(name = "{0} -> {1}")
  @CsvSource({"'1,2,3,4,7,45', FOURTH", "'1,2,3,7,44,45', FIFTH", "'1,2,7,43,44,45', NONE"})
  @DisplayName("T23: 보너스는 일치 개수에 더하지 않는다")
  void bonusIsNotAddedToMatchCount(String ticket, PrizeRank expected) {
    assertThat(winning.rankOf(parse(ticket))).isEqualTo(expected);
  }

  @Test
  @DisplayName("보너스 번호가 당첨 번호와 겹치면 거부한다")
  void rejectsBonusInsideMainNumbers() {
    assertThatThrownBy(
            () -> new WinningNumbers(LottoNumbers.of(1, 2, 3, 4, 5, 6), LottoNumber.of(6)))
        .isInstanceOf(InvalidLottoNumberException.class);
  }

  @Test
  @DisplayName("일치 개수가 0~6을 벗어나면 등수를 정하지 않는다")
  void rejectsImpossibleMatchCount() {
    assertThatThrownBy(() -> PrizeRank.of(7, false)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> PrizeRank.of(-1, false)).isInstanceOf(IllegalArgumentException.class);
    assertThat(PrizeRank.of(6, true)).isEqualTo(PrizeRank.FIRST);
    assertThat(PrizeRank.of(5, true).isWinning()).isTrue();
    assertThat(PrizeRank.NONE.isWinning()).isFalse();
  }

  private static LottoNumbers parse(String csv) {
    String[] parts = csv.split(",");
    int[] values = new int[parts.length];
    for (int i = 0; i < parts.length; i++) {
      values[i] = Integer.parseInt(parts[i].trim());
    }
    return LottoNumbers.of(values);
  }
}
