package com.lottery.lottery_service.lotto.validation.rule;

import static org.assertj.core.api.Assertions.assertThat;

import com.lottery.lottery_service.lotto.dto.LottoSet;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * EndDigitSumRule의 단위 테스트.
 *
 * <p>이 룰의 책임:
 *
 * <ul>
 *   <li>번호 6개의 끝자리 합을 계산한다.
 *   <li>끝자리 합이 14~42(포함) 범위면 true를 반환한다.
 *   <li>그 외면 false를 반환한다.
 * </ul>
 *
 * <p>현재 브랜치 기준으로 invalid input(null, 개수 불일치 등)은 개별 룰 테스트에서 반복 검증하지 않고, 도메인 규칙 자체만 검증한다.
 */
class EndDigitSumRuleTest {

  private final EndDigitSumRule rule = new EndDigitSumRule();

  /**
   * 끝자리 합이 허용 범위(14~42) 안이면 통과해야 한다.
   *
   * <p>예시: 1, 12, 23, 34, 35, 36 끝자리 합 = 1 + 2 + 3 + 4 + 5 + 6 = 21
   */
  @Test
  @DisplayName("끝자리 합이 14 이상 42 이하이면 통과한다")
  void validate_endDigitSumInAllowedRange_returnsTrue() {
    LottoSet set = new LottoSet(List.of(1, 12, 23, 34, 35, 36));

    boolean result = rule.validate(set);

    assertThat(result).isTrue();
  }

  /**
   * 끝자리 합이 허용 범위 밖이면 실패해야 한다.
   *
   * <p>예시: 1, 11, 21, 31, 41, 2 끝자리 합 = 1 + 1 + 1 + 1 + 1 + 2 = 7 → 14 미만이므로 실패
   */
  @Test
  @DisplayName("끝자리 합이 14 미만 또는 42 초과이면 실패한다")
  void validate_endDigitSumOutOfAllowedRange_returnsFalse() {
    LottoSet set = new LottoSet(List.of(1, 11, 21, 31, 41, 2));

    boolean result = rule.validate(set);

    assertThat(result).isFalse();
  }

  /**
   * 경계값도 정확히 검증한다.
   *
   * <p>하한 14, 상한 42는 모두 포함 범위이므로 통과해야 한다.
   */
  @Test
  @DisplayName("끝자리 합 경계값 14와 42는 모두 통과한다")
  void validate_endDigitSumOnBoundary_returnsTrue() {
    LottoSet minBoundary = new LottoSet(List.of(1, 1, 2, 3, 3, 4)); // 합 14
    LottoSet maxBoundary = new LottoSet(List.of(7, 7, 7, 7, 7, 7)); // 합 42

    assertThat(rule.validate(minBoundary)).isTrue();
    assertThat(rule.validate(maxBoundary)).isTrue();
  }

  /**
   * 룰 식별자와 실패 사유 코드를 고정한다.
   *
   * <p>파이프라인 결과/로그/디버깅에서 사용하는 메타데이터가 의도치 않게 바뀌지 않도록 보장한다.
   */
  @Test
  @DisplayName("룰 ID와 실패 사유 코드를 반환한다")
  void metadata_returnsExpectedIdentifiers() {
    assertThat(rule.id()).isEqualTo("EndDigitSum");
    assertThat(rule.reasonOnFail()).isEqualTo("END_SUM_OUT_OF_RANGE");
    assertThat(rule.enabled()).isTrue();
  }
}
