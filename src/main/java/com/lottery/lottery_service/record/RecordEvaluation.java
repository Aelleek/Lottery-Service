package com.lottery.lottery_service.record;

/**
 * 내역 기록 하나의 판정(openapi {@code Evaluation}). EVALUATED에서 {@code rank}가 {@code null}이면 낙첨이고, 다른 상태의
 * {@code null}은 아직 모름이다.
 *
 * @param state NOT_PUBLISHED(결과 게시 전), PENDING(게시됐지만 판정 전), EVALUATED
 * @param resultRevision 판정에 쓴 게시 버전. 게시 전이면 {@code null}
 * @param rank 등수(1~5). 낙첨이거나 모르면 {@code null}
 * @param prizePerGame 1게임 금액. 모르면 {@code null}
 */
public record RecordEvaluation(
    String state, Long resultRevision, Integer rank, Long prizePerGame) {}
