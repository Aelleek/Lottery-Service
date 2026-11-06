package com.lottery.lottery_service.result.source;

/** 결과 원천. 운영에서는 동행복권 결과 API, 테스트에서는 대역이다. */
public interface DrawSource {

  /**
   * 회차 결과를 읽는다. 네트워크 실패나 이상 응답도 예외 대신 {@link ParsedDraw.Invalid}로 돌려준다.
   *
   * @param roundNo 회차
   */
  ParsedDraw fetch(int roundNo);
}
