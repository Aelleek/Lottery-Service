package com.lottery.lottery_service.result.source;

/** 결과 API 응답을 읽은 결과. 미발표와 수집 실패를 구분한다(02 5절). */
public sealed interface ParsedDraw {

  /** 검증을 통과한 결과. */
  record Valid(OfficialDraw draw) implements ParsedDraw {}

  /** 아직 발표되지 않았다. */
  record NotAnnounced() implements ParsedDraw {}

  /**
   * 응답을 믿을 수 없다. 수집 실패로 기록한다.
   *
   * @param reason 내부 기록용 사유 코드
   */
  record Invalid(String reason) implements ParsedDraw {}
}
