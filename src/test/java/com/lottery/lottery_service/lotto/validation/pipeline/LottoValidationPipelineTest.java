package com.lottery.lottery_service.lotto.validation.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import com.lottery.lottery_service.lotto.dto.LottoSet;
import com.lottery.lottery_service.lotto.validation.rule.LottoValidationRule;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.Order;

/**
 * LottoValidationPipeline의 "오케스트레이션 책임"만 검증하는 테스트 클래스.
 *
 * <p>이 테스트 클래스의 역할:
 * <ul>
 *   <li>룰이 @Order 순서대로 실행되는지 검증한다.</li>
 *   <li>disabled 룰이 실행되지 않고 건너뛰어지는지 검증한다.</li>
 *   <li>첫 FAIL에서 short-circuit 되는지 검증한다.</li>
 *   <li>모든 활성 룰이 통과했을 때 PASS 결과가 조립되는지 검증한다.</li>
 * </ul>
 *
 * <p>중요:
 * <ul>
 *   <li>여기서는 실제 도메인 룰 구현(홀짝, 고저, 과거 1등 비교 등)을 검증하지 않는다.</li>
 *   <li>그건 각 Rule 단위 테스트의 책임이다.</li>
 *   <li>이 클래스는 파이프라인 자체의 제어 흐름과 결과 조립만 본다.</li>
 * </ul>
 */
class LottoValidationPipelineTest {

  private static final LottoSet SAMPLE_SET =
      new LottoSet(List.of(1, 2, 3, 4, 5, 6));

  /**
   * @Order가 작은 룰부터 실행되어야 한다.
   *
   * <p>이 테스트가 보장하는 것:
   * <ul>
   *   <li>생성자에 넣는 순서와 무관하게 @Order 기준으로 정렬된다.</li>
   *   <li>executedRuleIds에도 실제 실행 순서가 반영된다.</li>
   * </ul>
   */
  @Test
  @DisplayName("룰은 @Order 기준으로 정렬되어 실행된다")
  void validate_rulesOrderedByOrder_executesInSortedOrder() {
    // given
    List<String> callTrace = new ArrayList<>();

    LottoValidationRule rule3 = new ThirdPassRule(callTrace);
    LottoValidationRule rule1 = new FirstPassRule(callTrace);
    LottoValidationRule rule2 = new SecondPassRule(callTrace);

    // 일부러 뒤섞인 순서로 넣는다.
    LottoValidationPipeline pipeline =
        new LottoValidationPipeline(List.of(rule3, rule1, rule2));

    // when
    LottoValidationResult result = pipeline.validate(SAMPLE_SET);

    // then
    assertThat(result.isPass()).isTrue();
    assertThat(callTrace).containsExactly("RULE_1", "RULE_2", "RULE_3");
    assertThat(result.getExecutedRuleIds()).containsExactly("RULE_1", "RULE_2", "RULE_3");
  }

  /**
   * enabled()가 false인 룰은 실행 대상에서 제외되어야 한다.
   *
   * <p>이 테스트가 보장하는 것:
   * <ul>
   *   <li>disabled 룰은 validate(...)가 호출되지 않는다.</li>
   *   <li>executedRuleIds에도 포함되지 않는다.</li>
   * </ul>
   */
  @Test
  @DisplayName("비활성 룰은 실행하지 않고 건너뛴다")
  void validate_disabledRule_skipsValidation() {
    // given
    List<String> callTrace = new ArrayList<>();

    LottoValidationRule rule1 = new FirstPassRule(callTrace);
    LottoValidationRule disabledRule = new DisabledRule(callTrace);
    LottoValidationRule rule3 = new ThirdPassRule(callTrace);

    LottoValidationPipeline pipeline =
        new LottoValidationPipeline(List.of(rule3, disabledRule, rule1));

    // when
    LottoValidationResult result = pipeline.validate(SAMPLE_SET);

    // then
    assertThat(result.isPass()).isTrue();
    assertThat(callTrace).containsExactly("RULE_1", "RULE_3");
    assertThat(result.getExecutedRuleIds()).containsExactly("RULE_1", "RULE_3");
    assertThat(callTrace).doesNotContain("RULE_DISABLED");
    assertThat(result.getExecutedRuleIds()).doesNotContain("RULE_DISABLED");
  }

  /**
   * 첫 번째 FAIL이 발생하면 이후 룰은 실행되지 않아야 한다.
   *
   * <p>이 테스트가 보장하는 것:
   * <ul>
   *   <li>실패한 룰 ID와 사유 코드가 결과에 들어간다.</li>
   *   <li>그 이후 룰은 validate(...)가 호출되지 않는다.</li>
   *   <li>executedRuleIds는 실패 지점까지만 기록된다.</li>
   * </ul>
   */
  @Test
  @DisplayName("룰이 실패하면 즉시 중단하고 실패 결과를 반환한다")
  void validate_ruleFailsEarly_returnsFailWithoutExecutingRemainingRules() {
    // given
    List<String> callTrace = new ArrayList<>();

    LottoValidationRule rule1 = new FirstPassRule(callTrace);
    LottoValidationRule rule2 = new SecondFailRule(callTrace);
    LottoValidationRule rule3 = new ThirdPassRule(callTrace);

    LottoValidationPipeline pipeline =
        new LottoValidationPipeline(List.of(rule3, rule2, rule1));

    // when
    LottoValidationResult result = pipeline.validate(SAMPLE_SET);

    // then
    assertThat(result.isPass()).isFalse();
    assertThat(result.getFailedRuleId()).isEqualTo("RULE_2_FAIL");
    assertThat(result.getFailedReasonCode()).isEqualTo("RULE_2_FAILED");
    assertThat(callTrace).containsExactly("RULE_1", "RULE_2_FAIL");
    assertThat(callTrace).doesNotContain("RULE_3");
    assertThat(result.getExecutedRuleIds()).containsExactly("RULE_1", "RULE_2_FAIL");
  }

  /**
   * 모든 활성 룰이 통과하면 pass 결과를 반환해야 한다.
   *
   * <p>이 테스트가 보장하는 것:
   * <ul>
   *   <li>failedRuleId, failedReasonCode는 null이다.</li>
   *   <li>executedRuleIds에는 실행된 활성 룰들의 순서가 담긴다.</li>
   * </ul>
   */
  @Test
  @DisplayName("모든 활성 룰이 통과하면 PASS 결과를 반환한다")
  void validate_allRulesPass_returnsPass() {
    // given
    List<String> callTrace = new ArrayList<>();

    LottoValidationRule rule1 = new FirstPassRule(callTrace);
    LottoValidationRule rule2 = new SecondPassRule(callTrace);
    LottoValidationRule rule3 = new ThirdPassRule(callTrace);

    LottoValidationPipeline pipeline =
        new LottoValidationPipeline(List.of(rule2, rule3, rule1));

    // when
    LottoValidationResult result = pipeline.validate(SAMPLE_SET);

    // then
    assertThat(result.isPass()).isTrue();
    assertThat(result.getFailedRuleId()).isNull();
    assertThat(result.getFailedReasonCode()).isNull();
    assertThat(callTrace).containsExactly("RULE_1", "RULE_2", "RULE_3");
    assertThat(result.getExecutedRuleIds()).containsExactly("RULE_1", "RULE_2", "RULE_3");
  }

  // ---------------------------------------------------------------------------
  // 아래는 테스트 전용 fake rule 구현체들이다.
  // 실제 도메인 룰이 아니라, 파이프라인의 실행 순서/스킵/중단 동작을 검증하기 위한 더미 객체다.
  // ---------------------------------------------------------------------------

  private abstract static class BaseRule implements LottoValidationRule {
    protected final List<String> callTrace;

    protected BaseRule(List<String> callTrace) {
      this.callTrace = callTrace;
    }

    @Override
    public boolean validate(LottoSet set) {
      callTrace.add(id());
      return decision();
    }

    protected abstract boolean decision();
  }

  @Order(1)
  private static class FirstPassRule extends BaseRule {
    private FirstPassRule(List<String> callTrace) {
      super(callTrace);
    }

    @Override
    public String id() {
      return "RULE_1";
    }

    @Override
    protected boolean decision() {
      return true;
    }
  }

  @Order(2)
  private static class SecondPassRule extends BaseRule {
    private SecondPassRule(List<String> callTrace) {
      super(callTrace);
    }

    @Override
    public String id() {
      return "RULE_2";
    }

    @Override
    protected boolean decision() {
      return true;
    }
  }

  @Order(2)
  private static class SecondFailRule extends BaseRule {
    private SecondFailRule(List<String> callTrace) {
      super(callTrace);
    }

    @Override
    public String id() {
      return "RULE_2_FAIL";
    }

    @Override
    public String reasonOnFail() {
      return "RULE_2_FAILED";
    }

    @Override
    protected boolean decision() {
      return false;
    }
  }

  @Order(2)
  private static class DisabledRule extends BaseRule {
    private DisabledRule(List<String> callTrace) {
      super(callTrace);
    }

    @Override
    public String id() {
      return "RULE_DISABLED";
    }

    @Override
    public boolean enabled() {
      return false;
    }

    @Override
    protected boolean decision() {
      return true;
    }
  }

  @Order(3)
  private static class ThirdPassRule extends BaseRule {
    private ThirdPassRule(List<String> callTrace) {
      super(callTrace);
    }

    @Override
    public String id() {
      return "RULE_3";
    }

    @Override
    protected boolean decision() {
      return true;
    }
  }
}