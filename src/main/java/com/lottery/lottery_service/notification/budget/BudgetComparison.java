package com.lottery.lottery_service.notification.budget;

import java.time.LocalDate;

/**
 * 한 달의 예산 카운터와 메시지 비용 합계(0.1원 단위).
 *
 * @param month 달(1일)
 * @param counterReserved 카운터의 예약
 * @param counterSpent 카운터의 확정 사용
 * @param messageReserved 메시지 비용 합계의 예약
 * @param messageSpent 메시지 비용 합계의 확정 사용
 */
public record BudgetComparison(
    LocalDate month,
    long counterReserved,
    long counterSpent,
    long messageReserved,
    long messageSpent) {

  /** 카운터와 메시지 합계가 같은지. */
  public boolean matches() {
    return counterReserved == messageReserved && counterSpent == messageSpent;
  }
}
