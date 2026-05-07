package com.lottery.lottery_service.lotto.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * LottoSet 불변식 테스트.
 */
class LottoSetTest {

  @Test
  @DisplayName("숫자 6개로 LottoSet을 생성할 수 있다")
  void constructor_sixNumbers_createsInstance() {
    LottoSet lottoSet = new LottoSet(List.of(1, 2, 3, 4, 5, 6));

    assertThat(lottoSet.getNumbers()).containsExactly(1, 2, 3, 4, 5, 6);
  }

  @Test
  @DisplayName("숫자가 6개보다 적으면 예외를 던진다")
  void constructor_lessThanSixNumbers_throwsException() {
    assertThatThrownBy(() -> new LottoSet(List.of(1, 2, 3, 4, 5)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("6개");
  }

  @Test
  @DisplayName("숫자가 6개보다 많으면 예외를 던진다")
  void constructor_moreThanSixNumbers_throwsException() {
    assertThatThrownBy(() -> new LottoSet(List.of(1, 2, 3, 4, 5, 6, 7)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("6개");
  }

  @Test
  @DisplayName("숫자 리스트가 null이면 예외를 던진다")
  void constructor_nullNumbers_throwsException() {
    assertThatThrownBy(() -> new LottoSet(null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("null");
  }
}