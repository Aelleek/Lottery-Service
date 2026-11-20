package com.lottery.lottery_service.common.number;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DecimalVersionTest {

  @Test
  @DisplayName("ASCII 숫자 1~18자리는 그 값으로 읽는다")
  void readsDigits() {
    assertThat(DecimalVersion.parse("0")).isZero();
    assertThat(DecimalVersion.parse("007")).isEqualTo(7);
    assertThat(DecimalVersion.parse("999999999999999999")).isEqualTo(999_999_999_999_999_999L);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"", "+5", "-1", " 5", "5 ", "1e3", "0x10", "٥", "５", "1234567890123456789"})
  @DisplayName("부호, 공백, 지수, ASCII가 아닌 숫자, 19자리 이상은 NumberFormatException")
  void rejectsOffContract(String value) {
    assertThatThrownBy(() -> DecimalVersion.parse(value)).isInstanceOf(NumberFormatException.class);
  }

  @Test
  @DisplayName("null도 NumberFormatException")
  void rejectsNull() {
    assertThatThrownBy(() -> DecimalVersion.parse(null)).isInstanceOf(NumberFormatException.class);
  }

  @Test
  @DisplayName("Long.parseLong이 받는 부호·비ASCII 숫자를 여기서는 받지 않는다")
  void stricterThanParseLong() {
    assertThat(Long.parseLong("+5")).isEqualTo(5);
    assertThat(Long.parseLong("٥")).isEqualTo(5);
    assertThatThrownBy(() -> DecimalVersion.parse("+5")).isInstanceOf(NumberFormatException.class);
  }
}
