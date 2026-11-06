package com.lottery.lottery_service.record;

import java.util.Set;

/**
 * 가져오기 묶음 하나의 결과.
 *
 * @param lastSourceId 이번 묶음에서 마지막으로 처리한 원본 기록 ID. 다음 묶음은 이 뒤부터다
 * @param processed 이번 묶음에서 결과를 남긴 원본 수
 * @param finished 남은 원본이 없어 귀속의 가져오기를 끝냈으면 {@code true}
 * @param roundsAfterSalesClose 판매 마감 뒤에 가져와 알림 대상이 아닌 회차. 화면이 알린다(02 7.1절)
 */
public record ImportBatch(
    long lastSourceId, int processed, boolean finished, Set<Integer> roundsAfterSalesClose) {}
