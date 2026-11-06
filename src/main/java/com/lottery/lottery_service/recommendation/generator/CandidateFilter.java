package com.lottery.lottery_service.recommendation.generator;

import com.lottery.lottery_service.common.number.LottoNumbers;

/** 기본 추천의 생성 필터. 조건 추천에는 쓰지 않는다(D-028). */
@FunctionalInterface
public interface CandidateFilter {

  /** 아무것도 거르지 않는 필터. */
  CandidateFilter NONE = numbers -> true;

  /** 조합을 추천해도 되면 {@code true}. */
  boolean accepts(LottoNumbers numbers);
}
