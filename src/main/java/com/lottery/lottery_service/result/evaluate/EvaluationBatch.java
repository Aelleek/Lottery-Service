package com.lottery.lottery_service.result.evaluate;

/**
 * 판정 묶음 하나의 결과.
 *
 * @param lastRecordId 마지막으로 본 기록 ID. 다음 묶음은 이 뒤부터다
 * @param evaluated 이번 묶음에서 판정한 기록 수
 * @param finished 더 볼 기록이 없거나 게시본이 바뀌어 멈췄으면 {@code true}
 * @param superseded 게시본이 더 이상 현재 게시본이 아니어서 멈췄으면 {@code true}
 */
public record EvaluationBatch(
    long lastRecordId, int evaluated, boolean finished, boolean superseded) {}
