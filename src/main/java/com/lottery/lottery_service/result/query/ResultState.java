package com.lottery.lottery_service.result.query;

/** 화면에 보이는 회차 결과 상태(openapi {@code DrawResult.state}). 미발표와 수집 지연을 구분한다(02 5절). */
public enum ResultState {
  /** 게시본이 있다. */
  PUBLISHED,
  /** 아직 게시본이 없고, 마지막 수집 시도가 미발표였거나 아직 시도하지 않았다. */
  NOT_PUBLISHED,
  /** 아직 게시본이 없고, 마지막 수집 시도가 결과 API 호출이나 응답 검증에서 실패했다. */
  DELAYED
}
