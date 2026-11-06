package com.lottery.lottery_service.common.number;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class LottoNumbersTest {

  @Test
  @DisplayName("T01: 같은 6개를 순서만 바꾸면 같은 조합이다")
  void sameNumbersInDifferentOrderAreEqual() {
    LottoNumbers a = LottoNumbers.of(6, 1, 45, 23, 10, 2);
    LottoNumbers b = LottoNumbers.of(1, 2, 6, 10, 23, 45);

    assertThat(a).isEqualTo(b);
    assertThat(a.hashCode()).isEqualTo(b.hashCode());
    assertThat(a.key()).isEqualTo(b.key());
  }

  @Test
  @DisplayName("T01: 한 숫자만 바꾸면 다른 조합이다")
  void oneDifferentNumberMakesDifferentCombination() {
    LottoNumbers a = LottoNumbers.of(1, 2, 3, 4, 5, 6);
    LottoNumbers b = LottoNumbers.of(1, 2, 3, 4, 5, 7);

    assertThat(a).isNotEqualTo(b);
    assertThat(a.key()).isNotEqualTo(b.key());
  }

  @Test
  @DisplayName("키는 두 자리 오름차순 번호를 공백으로 이은 17자다")
  void keyIsZeroPaddedSortedSeventeenChars() {
    LottoNumbers numbers = LottoNumbers.of(45, 3, 1, 10, 2, 9);

    assertThat(numbers.key()).isEqualTo("01 02 03 09 10 45");
    assertThat(numbers.key()).hasSize(LottoNumbers.KEY_LENGTH);
    assertThat(LottoNumbers.of(1, 2, 3, 4, 5, 6).key()).isEqualTo("01 02 03 04 05 06");
  }

  @Test
  @DisplayName("목록은 오름차순 정수 배열로 나간다")
  void toListIsSortedIntegers() {
    assertThat(LottoNumbers.of(List.of(40, 1, 30, 20, 10, 2)).toList())
        .containsExactly(1, 2, 10, 20, 30, 40);
  }

  @Test
  @DisplayName("번호 범위 경계: 1과 45는 되고 0과 46은 안 된다")
  void rangeBoundaries() {
    assertThat(LottoNumbers.of(1, 2, 3, 43, 44, 45).toList()).contains(1, 45);

    assertThatThrownBy(() -> LottoNumbers.of(0, 2, 3, 4, 5, 6))
        .isInstanceOf(InvalidLottoNumberException.class);
    assertThatThrownBy(() -> LottoNumbers.of(1, 2, 3, 4, 5, 46))
        .isInstanceOf(InvalidLottoNumberException.class);
    assertThatThrownBy(() -> LottoNumbers.of(-1, 2, 3, 4, 5, 6))
        .isInstanceOf(InvalidLottoNumberException.class);
  }

  @Test
  @DisplayName("개수는 정확히 6개여야 한다")
  void sizeMustBeSix() {
    assertThatThrownBy(() -> LottoNumbers.of(1, 2, 3, 4, 5))
        .isInstanceOf(InvalidLottoNumberException.class);
    assertThatThrownBy(() -> LottoNumbers.of(1, 2, 3, 4, 5, 6, 7))
        .isInstanceOf(InvalidLottoNumberException.class);
    assertThatThrownBy(() -> LottoNumbers.of(List.of()))
        .isInstanceOf(InvalidLottoNumberException.class);
  }

  @Test
  @DisplayName("중복 번호와 빈 값은 거부한다")
  void rejectsDuplicatesAndNulls() {
    assertThatThrownBy(() -> LottoNumbers.of(1, 1, 3, 4, 5, 6))
        .isInstanceOf(InvalidLottoNumberException.class);
    assertThatThrownBy(() -> LottoNumbers.of(Arrays.asList(1, 2, null, 4, 5, 6)))
        .isInstanceOf(InvalidLottoNumberException.class);
    assertThatThrownBy(() -> LottoNumbers.of((List<Integer>) null))
        .isInstanceOf(InvalidLottoNumberException.class);
  }

  @Test
  @DisplayName("키를 읽으면 같은 조합으로 돌아온다")
  void keyRoundTrip() {
    LottoNumbers original = LottoNumbers.of(7, 19, 3, 44, 28, 11);

    assertThat(LottoNumbers.fromKey(original.key())).isEqualTo(original);
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(
      strings = {
        "",
        "1 2 3 4 5 6",
        "01 02 03 04 05",
        "01 02 03 04 05 06 07",
        "01,02,03,04,05,06",
        "01  02 03 04 05 06",
        " 01 02 03 04 05 06",
        "06 05 04 03 02 01",
        "01 01 03 04 05 06",
        "00 02 03 04 05 06",
        "01 02 03 04 05 46",
        "01 02 03 04 05 6a"
      })
  @DisplayName("정규 형식이 아닌 키는 거부한다")
  void rejectsNonCanonicalKeys(String key) {
    assertThatThrownBy(() -> LottoNumbers.fromKey(key))
        .isInstanceOf(InvalidLottoNumberException.class);
  }

  @Test
  @DisplayName("예외 메시지에 입력 번호를 넣지 않는다")
  void exceptionMessagesDoNotContainNumbers() {
    assertThatThrownBy(() -> LottoNumbers.fromKey("06 05 04 03 02 01"))
        .hasMessageNotContaining("06 05")
        .hasMessageNotContaining("01");
    assertThatThrownBy(() -> LottoNumbers.fromKey("x7 18 29 30 41 42"))
        .hasMessageNotContaining("18 29");
    assertThatThrownBy(() -> LottoNumbers.of(17, 17, 3, 4, 5, 6)).hasMessageNotContaining("17");
    assertThatThrownBy(() -> LottoNumber.of(46)).hasMessageNotContaining("46");
  }

  @Test
  @DisplayName("겹치는 번호 개수를 센다")
  void matchCount() {
    LottoNumbers a = LottoNumbers.of(1, 2, 3, 4, 5, 6);

    assertThat(a.matchCount(LottoNumbers.of(1, 2, 3, 40, 41, 42))).isEqualTo(3);
    assertThat(a.matchCount(LottoNumbers.of(40, 41, 42, 43, 44, 45))).isZero();
    assertThat(a.matchCount(a)).isEqualTo(6);
  }
}
