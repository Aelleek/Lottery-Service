package com.lottery.lottery_service.notification.budget;

/**
 * 한 달 예산 카운터(0.1원 단위).
 *
 * @param limit 월 한도
 * @param reserved 예약
 * @param spent 확정 사용
 */
public record BudgetUsage(long limit, long reserved, long spent) {}
