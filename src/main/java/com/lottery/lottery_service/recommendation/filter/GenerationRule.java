package com.lottery.lottery_service.recommendation.filter;

import com.lottery.lottery_service.common.number.LottoNumbers;

/**
 * 기본 추천의 생성 필터 하나. 상태를 갖지 않는다. {@code @Component}와 {@code @Order}를 붙이면 {@link
 * GenerationFilterPipeline}에 순서대로 들어간다.
 */
public interface GenerationRule {

  /** 내부 기록용 식별자. */
  String id();

  /** 조합을 통과시키면 {@code true}. */
  boolean accepts(LottoNumbers numbers, FilterContext context);
}
