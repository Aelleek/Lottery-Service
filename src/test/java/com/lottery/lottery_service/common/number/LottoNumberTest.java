package com.lottery.lottery_service.common.number;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class LottoNumberTest {

  @ParameterizedTest
  @ValueSource(ints = {1, 2, 44, 45})
  @DisplayName("1~45는 번호다")
  void acceptsRange(int value) {
    assertThat(LottoNumber.of(value).value()).isEqualTo(value);
  }

  @ParameterizedTest
  @ValueSource(ints = {Integer.MIN_VALUE, -1, 0, 46, 100, Integer.MAX_VALUE})
  @DisplayName("범위 밖은 거부한다")
  void rejectsOutOfRange(int value) {
    assertThatThrownBy(() -> LottoNumber.of(value)).isInstanceOf(InvalidLottoNumberException.class);
  }
}
