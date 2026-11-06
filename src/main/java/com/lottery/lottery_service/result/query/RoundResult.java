package com.lottery.lottery_service.result.query;

import com.lottery.lottery_service.result.domain.PrizeRank;
import com.lottery.lottery_service.result.domain.WinningNumbers;
import java.time.Instant;
import java.util.List;

/**
 * 한 회차의 결과 조회 값.
 *
 * @param round 회차
 * @param state 결과 상태
 * @param publicationVersion 게시 버전({@code lotto_round.publication_version}). 게시 전이면 {@code null}
 * @param winning 게시된 당첨 번호. 게시 전이면 {@code null}
 * @param prizes 게시본의 등수별 금액. 게시 전이면 빈 목록
 * @param sourceCheckedAt 원천을 마지막으로 확인한 시각. 모르면 {@code null}
 */
public record RoundResult(
    int round,
    ResultState state,
    Long publicationVersion,
    WinningNumbers winning,
    List<Prize> prizes,
    Instant sourceCheckedAt) {

  /** 금액을 얼마나 확보했는지(openapi {@code DrawResult.completeness}). */
  public String completeness() {
    if (winning == null) {
      return "NONE";
    }
    long known = prizes.stream().filter(p -> p.prizePerGame() != null).count();
    if (known == 0) {
      return "NUMBERS_ONLY";
    }
    return known == PrizeRank.values().length - 1 ? "COMPLETE" : "PARTIAL_PRIZES";
  }

  /** 등수의 1게임 금액. 낙첨이거나 모르면 {@code null}. */
  public Long prizePerGame(PrizeRank rank) {
    if (!rank.isWinning()) {
      return null;
    }
    int rankNo = rank.ordinal() + 1;
    return prizes.stream()
        .filter(p -> p.rank() == rankNo)
        .findFirst()
        .map(Prize::prizePerGame)
        .orElse(null);
  }
}
