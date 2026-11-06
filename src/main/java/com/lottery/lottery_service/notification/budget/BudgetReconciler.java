package com.lottery.lottery_service.notification.budget;

import com.lottery.lottery_service.common.job.JobStore;
import com.lottery.lottery_service.common.time.SeoulTime;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 매일 정리 때 지난달과 이번 달의 예산 카운터를 메시지 비용 합계와 대조한다(05 8.4절). 다르면 운영자 알림을 달마다 한 번 등록한다(07 2.1절 예산 카운터
 * 불일치).
 */
@Component
public class BudgetReconciler {

  /** 운영자 알림 종류. */
  public static final String ALERT_KIND = "BUDGET_MISMATCH";

  private final MessageBudgetStore budget;
  private final JobStore jobs;
  private final Clock clock;

  /** 대조기를 만든다. */
  public BudgetReconciler(MessageBudgetStore budget, JobStore jobs, Clock clock) {
    this.budget = budget;
    this.jobs = jobs;
    this.clock = clock;
  }

  /**
   * 대조한다. 호출한 쪽 트랜잭션에서 돈다.
   *
   * @return 카운터가 맞지 않은 달
   */
  public List<BudgetComparison> reconcile() {
    LocalDate thisMonth = SeoulTime.today(clock).withDayOfMonth(1);
    List<BudgetComparison> mismatched =
        List.of(budget.compare(thisMonth.minusMonths(1)), budget.compare(thisMonth)).stream()
            .filter(c -> !c.matches())
            .toList();
    for (BudgetComparison c : mismatched) {
      jobs.enqueue(
          JobStore.OPERATOR_ALERT,
          "budget-mismatch:" + c.month(),
          "{\"kind\":\""
              + ALERT_KIND
              + "\",\"month\":\""
              + c.month().toString().substring(0, 7)
              + "\",\"counterReserved\":"
              + c.counterReserved()
              + ",\"counterSpent\":"
              + c.counterSpent()
              + ",\"messageReserved\":"
              + c.messageReserved()
              + ",\"messageSpent\":"
              + c.messageSpent()
              + "}",
          clock.instant());
    }
    return mismatched;
  }
}
