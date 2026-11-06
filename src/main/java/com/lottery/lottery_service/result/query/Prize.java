package com.lottery.lottery_service.result.query;

/**
 * 게시본의 등수별 1게임 금액과 당첨 수. 모르는 값은 {@code null}이고 0과 다르다.
 *
 * @param rank 등수(1~5)
 * @param prizePerGame 1게임당 원화 금액
 * @param winnerCount 당첨 게임 수
 */
public record Prize(int rank, Long prizePerGame, Long winnerCount) {}
