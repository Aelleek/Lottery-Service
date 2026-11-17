package com.lottery.lottery_service.result.query;

import static org.assertj.core.api.Assertions.assertThat;

import com.lottery.lottery_service.common.number.LottoNumber;
import com.lottery.lottery_service.common.number.LottoNumbers;
import com.lottery.lottery_service.result.domain.WinningNumbers;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RoundResultTest {

  private static final WinningNumbers WIN =
      new WinningNumbers(LottoNumbers.of(1, 2, 3, 4, 5, 6), LottoNumber.of(7));

  @Test
  @DisplayName("금액 확보 정도: 번호 없음 NONE, 금액 없음 NUMBERS_ONLY, 1~5등 중 일부 PARTIAL_PRIZES, 모두 COMPLETE")
  void completeness() {
    List<Prize> partial =
        List.of(
            new Prize(1, 2_000_000_000L, 10L),
            new Prize(2, 50_000_000L, 60L),
            new Prize(3, 1_500_000L, 3_000L),
            new Prize(4, 50_000L, null),
            new Prize(5, null, null));
    List<Prize> complete =
        List.of(
            new Prize(1, 2_000_000_000L, 10L),
            new Prize(2, 50_000_000L, 60L),
            new Prize(3, 1_500_000L, 3_000L),
            new Prize(4, 50_000L, null),
            new Prize(5, 5_000L, null));

    assertThat(result(null, List.of()).completeness()).isEqualTo("NONE");
    assertThat(result(WIN, List.of(new Prize(1, null, null))).completeness())
        .isEqualTo("NUMBERS_ONLY");
    assertThat(result(WIN, partial).completeness()).isEqualTo("PARTIAL_PRIZES");
    assertThat(result(WIN, complete).completeness()).isEqualTo("COMPLETE");
  }

  private static RoundResult result(WinningNumbers winning, List<Prize> prizes) {
    return new RoundResult(
        1200,
        winning == null ? ResultState.NOT_PUBLISHED : ResultState.PUBLISHED,
        winning == null ? null : 1L,
        winning,
        prizes,
        null);
  }
}
