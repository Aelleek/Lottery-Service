package com.lottery.lottery_service.lotto.validation.rule;

import static org.assertj.core.api.Assertions.assertThat;

import com.lottery.lottery_service.lotto.dto.LottoSet;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * EndDigitDuplicateGte4Rule의 단위 테스트.
 *
 * <p>이 룰의 책임:
 * <ul>
 *   <li>번호 6개의 끝자리(1의 자리)를 센다.</li>
 *   <li>같은 끝자리가 4개 이상이면 false를 반환한다.</li>
 *   <li>그 미만이면 true를 반환한다.</li>
 *   <li>입력이 비정상이면 false를 반환한다.</li>
 * </ul>
 */
class EndDigitDuplicateGte4RuleTest {

  private final EndDigitDuplicateGte4Rule rule = new EndDigitDuplicateGte4Rule();

  /**
   * 같은 끝자리가 4개 미만이면 통과해야 한다.
   *
   * <p>끝자리 중복이 있더라도 threshold(4) 미만이면 FAIL 되지 않음을 보장한다.
   */
  @Test
  @DisplayName("같은 끝자리가 4개 미만이면 통과한다")
  void validate_endDigitDuplicateLessThanFour_returnsTrue() {
    // 끝자리 1이 3개(1, 11, 21)까지만 존재
    LottoSet set = new LottoSet(List.of(1, 11, 21, 32, 43, 44));

    boolean result = rule.validate(set);

    assertThat(result).isTrue();
  }

  /**
   * 같은 끝자리가 4개 이상이면 실패해야 한다.
   *
   * <p>이 테스트는 룰의 핵심 threshold 정책을 고정한다.
   */
  @Test
  @DisplayName("같은 끝자리가 4개 이상이면 실패한다")
  void validate_endDigitDuplicateAtLeastFour_returnsFalse() {
    // 끝자리 1이 4개(1, 11, 21, 31)
    LottoSet set = new LottoSet(List.of(1, 11, 21, 31, 42, 43));

    boolean result = rule.validate(set);

    assertThat(result).isFalse();
  }

  /**
   * 룰 식별자와 실패 사유 코드를 고정한다.
   */
  @Test
  @DisplayName("룰 ID와 실패 사유 코드를 반환한다")
  void metadata_returnsExpectedIdentifiers() {
    assertThat(rule.id()).isEqualTo("EndDigitDupGte4");
    assertThat(rule.reasonOnFail()).isEqualTo("END_DIGIT_DUP_GE_4");
    assertThat(rule.enabled()).isTrue();
  }
}