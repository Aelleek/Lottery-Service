package com.lottery.lottery_service.lotto.validation.rule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.lottery.lottery_service.lotto.dto.LottoSet;
import com.lottery.lottery_service.lotto.validation.cache.LottoWinnerDataCache;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * NotEqualToPastFirstPrizeRule의 단위 테스트.
 *
 * <p>이 테스트 클래스의 역할:
 *
 * <ul>
 *   <li>입력 번호 6개를 정렬 + 공백 조인 포맷으로 정규화하는지 검증한다.
 *   <li>정규화 문자열이 캐시에 존재하면 FAIL(false) 하는지 검증한다.
 *   <li>캐시에 없으면 PASS(true) 하는지 검증한다.
 *   <li>입력 자체가 잘못되었을 때 예외를 던지는지 검증한다.
 * </ul>
 *
 * <p>중요:
 *
 * <ul>
 *   <li>이 테스트는 실제 DB를 보지 않는다.
 *   <li>이 테스트는 실제 캐시 로딩을 보지 않는다.
 *   <li>캐시의 contains(normalized) 호출 계약만 검증한다.
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class NotEqualToPastFirstPrizeRuleTest {

  @Mock private LottoWinnerDataCache cache;

  @InjectMocks private NotEqualToPastFirstPrizeRule rule;

  /**
   * 과거 1등 번호와 동일하지 않으면 통과해야 한다.
   *
   * <p>이 테스트가 보장하는 것:
   *
   * <ul>
   *   <li>입력 번호가 정렬되어 "1 2 3 4 5 6" 형태로 정규화된다.
   *   <li>cache.contains(normalized)가 false면 validate(...)는 true를 반환한다.
   * </ul>
   */
  @Test
  @DisplayName("과거 1등 조합과 다르면 통과한다")
  void validate_numbersNotInPastWinners_returnsTrue() {
    // given
    LottoSet set = new LottoSet(List.of(6, 1, 3, 2, 5, 4));

    given(cache.contains("1 2 3 4 5 6")).willReturn(false);

    // when
    boolean result = rule.validate(set);

    // then
    assertThat(result).isTrue();
    verify(cache).contains("1 2 3 4 5 6");
  }

  /**
   * 과거 1등 번호와 완전히 동일하면 실패해야 한다.
   *
   * <p>이 테스트가 보장하는 것:
   *
   * <ul>
   *   <li>입력 순서가 달라도 내부에서 정렬 후 비교한다.
   *   <li>cache.contains(normalized)가 true면 validate(...)는 false를 반환한다.
   * </ul>
   */
  @Test
  @DisplayName("과거 1등 조합과 완전히 같으면 실패한다")
  void validate_numbersMatchPastWinner_returnsFalse() {
    // given
    LottoSet set = new LottoSet(List.of(41, 12, 25, 3, 34, 1));

    given(cache.contains("1 3 12 25 34 41")).willReturn(true);

    // when
    boolean result = rule.validate(set);

    // then
    assertThat(result).isFalse();
    verify(cache).contains("1 3 12 25 34 41");
  }

  /**
   * LottoSet의 숫자 개수가 6개가 아니면 예외를 던져야 한다.
   *
   * <p>이 테스트가 보장하는 것:
   *
   * <ul>
   *   <li>비정상 입력은 캐시 조회 전에 차단된다.
   *   <li>룰이 입력 방어 책임도 가지고 있음을 고정한다.
   * </ul>
   */
  @Test
  @DisplayName("숫자 개수가 6개가 아니면 예외를 던진다")
  void validate_numbersSizeIsNotSix_throwsException() {
    // given
    LottoSet set = new LottoSet(List.of(1, 2, 3, 4, 5));

    // when & then
    assertThatThrownBy(() -> rule.validate(set))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("exactly 6 numbers");
  }

  /**
   * numbers 자체가 null이면 예외를 던져야 한다.
   *
   * <p>이 테스트가 보장하는 것:
   *
   * <ul>
   *   <li>null 입력도 방어적으로 차단한다.
   *   <li>캐시 조회 전에 예외가 발생한다.
   * </ul>
   */
  @Test
  @DisplayName("숫자 목록이 null이면 예외를 던진다")
  void validate_numbersIsNull_throwsException() {
    // given
    LottoSet set = new LottoSet(null);

    // when & then
    assertThatThrownBy(() -> rule.validate(set))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("exactly 6 numbers");
  }

  /**
   * 이 룰의 메타 정보도 같이 고정해 둔다.
   *
   * <p>이 테스트가 보장하는 것:
   *
   * <ul>
   *   <li>룰 ID가 파이프라인/로그/메트릭에서 기대하는 값과 일치한다.
   *   <li>실패 사유 코드가 현재 구현과 일치한다.
   * </ul>
   */
  @Test
  @DisplayName("룰 ID와 실패 사유 코드를 반환한다")
  void metadata_returnsExpectedIdentifiers() {
    assertThat(rule.id()).isEqualTo("NOT_EQUAL_TO_PAST_FIRST_PRIZE");
    assertThat(rule.reasonOnFail()).isEqualTo("EQUALS_PAST_FIRST_PRIZE");
  }
}
