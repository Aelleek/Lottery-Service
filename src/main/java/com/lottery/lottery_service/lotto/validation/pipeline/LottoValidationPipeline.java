package com.lottery.lottery_service.lotto.validation.pipeline;

import com.lottery.lottery_service.lotto.dto.LottoSet;
import com.lottery.lottery_service.lotto.validation.rule.LottoValidationRule;
import java.util.ArrayList;
import java.util.List;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.stereotype.Component;

/**
 * [컴포넌트] 검증 파이프라인 오케스트레이터.
 *
 * <p>책임(Responsibility) - 주입된 룰들을 @Order 순서대로 실행한다. - disabled 룰은 건너뛴다. - 첫 FAIL에서 즉시
 * 중단(Short-circuit)하고, 실패한 룰의 ID/사유코드와 지금까지 실행(시도)한 룰 ID 목록을 LottoValidationResult에 담아 반환한다. - 모든
 * 활성 룰 통과 시 PASS 결과를 반환한다.
 *
 * <p>비책임(Non-Responsibility) - 개별 도메인 판정 로직(과거 1등과 동일 여부 등)은 룰 구현체가 담당한다.
 */
@Component
public class LottoValidationPipeline {

  /** 실행할 룰 목록. - 생성자에서 불변 정렬 리스트로 고정하여, 런타임 중 외부 변경을 방지한다. */
  private final List<LottoValidationRule> rules;

  public LottoValidationPipeline(List<LottoValidationRule> rules) {
    // 스프링이 같은 타입의 빈들을 모두 수집해 주입해준다(List<LottoValidationRule>).
    // @Order/Ordered가 붙은 경우 그 순서대로 실행되도록 정렬한다.
    List<LottoValidationRule> sorted = new ArrayList<>(rules);
    sorted.sort(AnnotationAwareOrderComparator.INSTANCE);
    this.rules = List.copyOf(sorted); // 불변화
  }

  /**
   * 단일 추천 세트를 파이프라인에 태워 검증한다.
   *
   * @param set 추천 번호 1세트
   * @return PASS 또는 FAIL(+ 실패 룰/사유/실행된 룰 목록)
   * @throws RuntimeException 룰 수행 중 시스템 예외 발생 시 그대로 전파하여 호출자가 중단 판단
   */
  public LottoValidationResult validate(LottoSet set) {
    // 디버깅/메트릭을 위해 실행한(시도한) 룰들의 ID를 담아둔다.
    List<String> executed = new ArrayList<>();

    for (LottoValidationRule rule : rules) {
      // 비활성 룰은 스킵
      if (!rule.enabled()) continue;

      // 실행 시도 목록에 추가(실패한 경우 어디까지 돌았는지 추적 가능)
      executed.add(rule.id());

      // 실제 판정 수행
      boolean ok = rule.validate(set);

      // 첫 실패에서 즉시 중단: 실패한 룰 ID/사유코드를 결과에 담아 반환
      if (!ok) {
        return LottoValidationResult.fail(rule.id(), rule.reasonOnFail(), executed);
      }
    }

    // 모든 활성화된 룰을 통과했다면 PASS
    return LottoValidationResult.pass(executed);
  }
}
