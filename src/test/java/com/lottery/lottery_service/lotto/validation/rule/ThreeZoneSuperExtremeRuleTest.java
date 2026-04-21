package com.lottery.lottery_service.lotto.validation.rule;

import static org.assertj.core.api.Assertions.assertThat;

import com.lottery.lottery_service.lotto.dto.LottoSet;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ThreeZoneSuperExtremeRule의 단위 테스트.
 *
 * <p>이 룰의 책임:
 * <ul>
 *   <li>번호 6개를 3구간(1~15 / 16~30 / 31~45)으로 나눈다.</li>
 *   <li>한 구간에 6개가 전부 몰린 6-0-0 패턴이면 false를 반환한다.</li>
 *   <li>그 외 분포는 true를 반환한다.</li>
 * </ul>
 *
 * <p>현재 브랜치 기준으로 invalid input(null, 개수 불일치, 범위 밖 숫자 등)은
 * 개별 룰 테스트에서 반복 검증하지 않고, 도메인 규칙 의미 자체만 검증한다.
 */
class ThreeZoneSuperExtremeRuleTest {

  private final ThreeZoneSuperExtremeRule rule = new ThreeZoneSuperExtremeRule();

  /**
   * 번호가 여러 구간에 걸쳐 분포하면 통과해야 한다.
   *
   * <p>예:
   * 1, 7, 16, 24, 31, 45
   * -> z1=2, z2=2, z3=2
   * -> 6-0-0 패턴이 아니므로 통과
   */
  @Test
  @DisplayName("번호가 여러 구간에 분포하면 통과한다")
  void validate_numbersSpreadAcrossZones_returnsTrue() {
    LottoSet set = new LottoSet(List.of(1, 7, 16, 24, 31, 45));

    boolean result = rule.validate(set);

    assertThat(result).isTrue();
  }

  /**
   * 한 구간에 6개가 전부 몰리면 실패해야 한다.
   *
   * <p>예:
   * 1, 2, 3, 4, 5, 6
   * -> z1=6, z2=0, z3=0
   * -> super extreme 패턴이므로 실패
   */
  @Test
  @DisplayName("한 구간에 6개가 전부 몰리면 실패한다")
  void validate_allNumbersInSingleZone_returnsFalse() {
    LottoSet zone1Only = new LottoSet(List.of(1, 2, 3, 4, 5, 6));
    LottoSet zone2Only = new LottoSet(List.of(16, 17, 18, 19, 20, 21));
    LottoSet zone3Only = new LottoSet(List.of(31, 32, 33, 34, 35, 36));

    assertThat(rule.validate(zone1Only)).isFalse();
    assertThat(rule.validate(zone2Only)).isFalse();
    assertThat(rule.validate(zone3Only)).isFalse();
  }

  /**
   * 5-1-0처럼 매우 치우쳐 있어도 6-0-0이 아니면 통과해야 한다.
   *
   * <p>이 테스트는 룰이 "극단적 분포 전체"를 막는 게 아니라,
   * 오직 super extreme(6-0-0)만 차단한다는 현재 정책을 고정한다.
   */
  @Test
  @DisplayName("치우친 분포여도 6대0대0이 아니면 통과한다")
  void validate_skewedButNotSuperExtreme_returnsTrue() {
    LottoSet set = new LottoSet(List.of(1, 2, 3, 4, 5, 31)); // z1=5, z3=1

    boolean result = rule.validate(set);

    assertThat(result).isTrue();
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
    assertThat(rule.id()).isEqualTo("ThreeZoneSuperExtreme");
    assertThat(rule.reasonOnFail()).isEqualTo("THREE_ZONE_6_0_0");
    assertThat(rule.enabled()).isTrue();
  }
}