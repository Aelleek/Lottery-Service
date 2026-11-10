package com.lottery.lottery_service.recommendation.filter;

import com.lottery.lottery_service.common.number.LottoNumbers;
import com.lottery.lottery_service.recommendation.generator.CandidateFilter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.stereotype.Component;

/**
 * 생성 필터 7개를 {@code @Order} 순서로 적용하고 첫 실패에서 멈춘다(02 2.2절). 걸린 사유는 응답에 싣지 않는다.
 *
 * <p>필터를 바꾸면 {@link #VERSION}을 올린다. 추천 요청마다 이 버전을 기록한다.
 */
@Component
public class GenerationFilterPipeline {

  /** 지금 필터 버전. */
  public static final String VERSION = "FILTER_V1";

  private final List<GenerationRule> rules;

  /** 등록된 필터를 순서대로 정렬해 둔다. */
  public GenerationFilterPipeline(List<GenerationRule> rules) {
    List<GenerationRule> sorted = new ArrayList<>(rules);
    sorted.sort(AnnotationAwareOrderComparator.INSTANCE);
    this.rules = List.copyOf(sorted);
  }

  /** 처음 걸린 필터의 식별자. 모두 통과하면 빈 값. */
  public Optional<String> firstFailure(LottoNumbers numbers, FilterContext context) {
    for (GenerationRule rule : rules) {
      if (!rule.accepts(numbers, context)) {
        return Optional.of(rule.id());
      }
    }
    return Optional.empty();
  }

  /** 한 추천 요청에 쓸 생성기용 필터. */
  public CandidateFilter forContext(FilterContext context) {
    return numbers -> firstFailure(numbers, context).isEmpty();
  }

  /** 적용 순서대로 필터 식별자. */
  public List<String> ruleIds() {
    return rules.stream().map(GenerationRule::id).toList();
  }
}
