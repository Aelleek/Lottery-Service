package com.lottery.lottery_service.result.source;

/**
 * 결과 API가 준 한 등수의 금액과 당첨 수.
 *
 * @param rank 등수(1~3)
 * @param prizePerGame 1게임 금액. 없거나 0이면 {@code null}(확인 중)
 * @param winnerCount 당첨 게임 수. 없으면 {@code null}
 */
public record RankPrize(int rank, Long prizePerGame, Long winnerCount) {}
