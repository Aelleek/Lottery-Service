package com.lottery.lottery_service.lotto.validation.rule;

import static org.assertj.core.api.Assertions.assertThat;

import com.lottery.lottery_service.lotto.dto.LottoSet;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ArithmeticComplexityMax6Rule의 단위 테스트.
 *
 * <p>이 룰의 책임:
 * <ul>
 *   <li>정렬된 6개 숫자의 모든 (aj - ai) 차이값을 구한다.</li>
 *   <li>서로 다른 차이값 개수(AC)를 계산한다.</li>
 *   <li>AC가 6 이하면 false, 7 이상이면 true를 반환한다.</li>
 * </ul>
 *
 * <p>현재 브랜치 기준으로 invalid input(null, 개수 불일치 등)은
 * 개별 룰 테스트에서 반복 검증하지 않고, 도메인 규칙 의미 자체만 검증한다.
 */
class ArithmeticComplexityMax6RuleTest {

  private final ArithmeticComplexityMax6Rule rule = new ArithmeticComplexityMax6Rule();

  /**
   * 차이값 종류가 충분히 많으면 통과해야 한다.
   *
   * <p>예:
   * 1, 2, 4, 8, 16, 32
   * -> pairwise diff가 매우 다양해서 AC > 6
   *
   * <p>입력을 일부러 정렬되지 않은 순서로 넣어,
   * 룰 내부의 sort가 실제로 동작해도 결과가 올바른지 함께 본다.
   */
  @Test
  @DisplayName("산술적 복잡도 AC가 7 이상이면 통과한다")
  void validate_acGreaterThanSix_returnsTrue() {
    LottoSet set = new LottoSet(List.of(32, 1, 16, 2, 8, 4));

    boolean result = rule.validate(set);

    assertThat(result).isTrue();
  }

  /**
   * 차이값 종류가 적으면 실패해야 한다.
   *
   * <p>예:
   * 1, 2, 3, 4, 5, 6
   * -> 가능한 차이값 종류는 {1,2,3,4,5} 이므로 AC=5
   * -> AC<=6 이므로 실패
   */
  @Test
  @DisplayName("산술적 복잡도 AC가 6 이하면 실패한다")
  void validate_acLessThanOrEqualSix_returnsFalse() {
    LottoSet set = new LottoSet(List.of(1, 2, 3, 4, 5, 6));

    boolean result = rule.validate(set);

    assertThat(result).isFalse();
  }

  /**
   * 경계 근처 의미를 분명히 하기 위한 테스트.
   *
   * <p>이 테스트는 룰이 "AC가 작다"는 이유로 등차수열 같은 단순 패턴을
   * 잘 차단하고 있음을 더 명확히 보여준다.
   */
  @Test
  @DisplayName("등차수열 패턴은 낮은 AC로 인해 실패한다")
  void validate_arithmeticProgression_returnsFalse() {
    LottoSet set = new LottoSet(List.of(5, 10, 15, 20, 25, 30));

    boolean result = rule.validate(set);

    assertThat(result).isFalse();
  }

  /**
   * 룰 식별자와 실패 사유 코드를 고정한다.
   *
   * <p>파이프라인 결과/로그/디버깅에서 사용하는 메타데이터가
   * 의도치 않게 바뀌지 않도록 보장한다.
   */
  @Test
  @DisplayName("룰 ID와 실패 사유 코드를 반환한다")
  void metadata_returnsExpectedIdentifiers() {
    assertThat(rule.id()).isEqualTo("ArithmeticComplexityMax6");
    assertThat(rule.reasonOnFail()).isEqualTo("AC_LE_6");
    assertThat(rule.enabled()).isTrue();
  }
}