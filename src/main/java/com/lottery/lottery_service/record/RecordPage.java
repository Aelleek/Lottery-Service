package com.lottery.lottery_service.record;

import java.util.List;

/**
 * 내역 한 쪽(06 2.1절).
 *
 * @param selectedRound 조회한 회차. 회차 일정이 없으면 {@code null}
 * @param items 최대 10개
 * @param nextCursor 다음 쪽 cursor. 없으면 {@code null}
 * @param emptyReason 0건일 때의 이유. 있으면 {@code null}이 아니다
 */
public record RecordPage(
    Integer selectedRound, List<RecordView> items, String nextCursor, EmptyReason emptyReason) {

  /** 0건의 이유. 화면은 {@link #NO_CURRENT_RECORDS}일 때만 추천 화면으로 옮긴다. */
  public enum EmptyReason {
    /** 현재 회차를 필터 없이 봤는데 기록이 없다. */
    NO_CURRENT_RECORDS,
    /** 필터 결과가 없다. */
    NO_MATCH,
    /** 사용자가 고른 회차에 기록이 없다. */
    NO_RECORDS
  }
}
