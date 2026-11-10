package com.lottery.lottery_service.recommendation;

/** 생성 권한이 만료돼 다른 생성이 시작됐다. 이 생성의 결과는 저장하지 않는다. */
public class GenerationSupersededException extends RuntimeException {

  /** 주체 ID와 이 생성의 번호로 만든다. */
  public GenerationSupersededException(long subjectId, long fence) {
    super("generation superseded: subject=" + subjectId + ", fence=" + fence);
  }
}
