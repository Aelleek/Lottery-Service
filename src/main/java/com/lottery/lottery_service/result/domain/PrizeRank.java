package com.lottery.lottery_service.result.domain;

/** 당첨 등수. 보너스 번호는 2등을 가를 때만 쓰고 일치 개수에 더하지 않는다. */
public enum PrizeRank {
  /** 6개 일치. */
  FIRST,
  /** 5개 일치와 보너스 번호 일치. */
  SECOND,
  /** 5개 일치. */
  THIRD,
  /** 4개 일치. */
  FOURTH,
  /** 3개 일치. */
  FIFTH,
  /** 낙첨(2개 이하 일치). */
  NONE;

  /** 일치 개수와 보너스 일치 여부로 등수를 정한다. */
  public static PrizeRank of(int matchCount, boolean bonusMatched) {
    if (matchCount < 0 || matchCount > 6) {
      throw new IllegalArgumentException("일치 개수는 0~6이어야 합니다: " + matchCount);
    }
    return switch (matchCount) {
      case 6 -> FIRST;
      case 5 -> bonusMatched ? SECOND : THIRD;
      case 4 -> FOURTH;
      case 3 -> FIFTH;
      default -> NONE;
    };
  }

  /** 당첨 등수인지(낙첨이 아닌지). */
  public boolean isWinning() {
    return this != NONE;
  }
}
