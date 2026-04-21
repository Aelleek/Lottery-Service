package com.lottery.lottery_service.lotto.validation.rule;

import static org.assertj.core.api.Assertions.assertThat;

import com.lottery.lottery_service.lotto.dto.LottoSet;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * LowHighZeroSixRule의 단위 테스트.
 *
 * <p>이 룰의 책임:
 * <ul>
 *   <li>1~22를 low, 23~45를 high로 나눈다.</li>
 *   <li>low:high 분포가 0:6 또는 6:0 같은 극단 패턴이면 false를 반환한다.</li>
 *   <li>잘못된 입력(null, 개수 불일치, 원소 null)도 false로 처리한다.</li>
 * </ul>
 */
class LowHighZeroSixRuleTest {

  private final LowHighZeroSixRule rule = new LowHighZeroSixRule();

  /**
   * low와 high가 섞여 있으면 통과해야 한다.
   *
   * <p>극단 분포가 아니라면 추천 후보로 유지된다는 점을 보장한다.
   */
  @Test
  @DisplayName("저번호와 고번호가 섞여 있으면 통과한다")
  void validate_mixedLowHigh_returnsTrue() {
    LottoSet set = new LottoSet(List.of(1, 7, 12, 24, 33, 45));

    boolean result = rule.validate(set);

    assertThat(result).isTrue();
  }

  /**
   * 전부 low이거나 전부 high이면 실패해야 한다.
   *
   * <p>1~22만 6개 또는 23~45만 6개인 극단 패턴을 차단한다.
   */
  @Test
  @DisplayName("저고 분포가 0대6 또는 6대0이면 실패한다")
  void validate_allLowOrAllHigh_returnsFalse() {
    LottoSet allLow = new LottoSet(List.of(1, 2, 3, 4, 5, 6));
    LottoSet allHigh = new LottoSet(List.of(23, 24, 30, 31, 40, 45));

    assertThat(rule.validate(allLow)).isFalse();
    assertThat(rule.validate(allHigh)).isFalse();
  }

  /**
   * 룰 메타데이터를 고정한다.
   */
  @Test
  @DisplayName("룰 ID와 실패 사유 코드를 반환한다")
  void metadata_returnsExpectedIdentifiers() {
    assertThat(rule.id()).isEqualTo("LowHighZeroSix");
    assertThat(rule.reasonOnFail()).isEqualTo("LOW_HIGH_0_6");
    assertThat(rule.enabled()).isTrue();
  }
}