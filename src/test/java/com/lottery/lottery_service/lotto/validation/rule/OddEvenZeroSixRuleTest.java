package com.lottery.lottery_service.lotto.validation.rule;

import static org.assertj.core.api.Assertions.assertThat;

import com.lottery.lottery_service.lotto.dto.LottoSet;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * OddEvenZeroSixRule의 단위 테스트.
 *
 * <p>이 룰의 책임:
 *
 * <ul>
 *   <li>짝/홀 분포가 0:6 또는 6:0 같은 극단 패턴인지 검사한다.
 *   <li>극단 패턴이면 false, 일반적인 혼합 분포면 true를 반환한다.
 *   <li>잘못된 입력(null, 개수 불일치, 원소 null)도 false로 처리한다.
 * </ul>
 */
class OddEvenZeroSixRuleTest {

  private final OddEvenZeroSixRule rule = new OddEvenZeroSixRule();

  /**
   * 짝/홀이 섞여 있으면 통과해야 한다.
   *
   * <p>이 테스트는 정상적인 혼합 분포가 FAIL 처리되지 않음을 보장한다.
   */
  @Test
  @DisplayName("짝수와 홀수가 섞여 있으면 통과한다")
  void validate_mixedOddEven_returnsTrue() {
    LottoSet set = new LottoSet(List.of(1, 2, 3, 4, 5, 6));

    boolean result = rule.validate(set);

    assertThat(result).isTrue();
  }

  /**
   * 전부 짝수이거나 전부 홀이면 실패해야 한다.
   *
   * <p>이 테스트는 룰이 막으려는 핵심 극단 패턴을 고정한다.
   */
  @Test
  @DisplayName("짝홀 분포가 0대6 또는 6대0이면 실패한다")
  void validate_allOddOrAllEven_returnsFalse() {
    LottoSet allOdd = new LottoSet(List.of(1, 3, 5, 7, 9, 11));
    LottoSet allEven = new LottoSet(List.of(2, 4, 6, 8, 10, 12));

    assertThat(rule.validate(allOdd)).isFalse();
    assertThat(rule.validate(allEven)).isFalse();
  }

  /**
   * 룰 식별자와 실패 사유 코드를 고정한다.
   *
   * <p>파이프라인 결과/로그/디버깅에서 사용하는 메타데이터가 바뀌지 않도록 보장한다.
   */
  @Test
  @DisplayName("룰 ID와 실패 사유 코드를 반환한다")
  void metadata_returnsExpectedIdentifiers() {
    assertThat(rule.id()).isEqualTo("OddEvenZeroSix");
    assertThat(rule.reasonOnFail()).isEqualTo("ODD_EVEN_0_6");
    assertThat(rule.enabled()).isTrue();
  }
}
