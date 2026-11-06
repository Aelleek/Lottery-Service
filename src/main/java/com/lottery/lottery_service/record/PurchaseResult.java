package com.lottery.lottery_service.record;

import java.util.List;

/**
 * 구매 등록 결과.
 *
 * @param roundNo 회차
 * @param items 입력 순서대로 합친 항목 결과
 * @param afterSalesClose 판매 마감 뒤 등록이면 {@code true}. 화면이 알림 제외를 안내한다(02 7.1절)
 */
public record PurchaseResult(int roundNo, List<PurchaseItem> items, boolean afterSalesClose) {}
