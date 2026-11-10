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

  /** 이번 달(서울) 예산 행을 만든다. 이미 있으면 그대로 둔다. */
  public void openMonth() {
    budget.ensureMonth(SeoulTime.today(clock));
  }

  /** 월 예산 사용(예약+확정)이 이 비율에 닿으면 알린다(07 2.1절). */
  public static final int USAGE_ALERT_PERCENT = 80;

  /** 사용량 알림 종류. */
  public static final String USAGE_ALERT_KIND = "BUDGET_THRESHOLD";

  /**
   * 이번 달 예산 사용이 80%에 닿았으면 운영자 알림을 달마다 한 번 등록한다. 호출한 쪽 트랜잭션에서 돈다.
   *
   * @return 이번에 등록했으면 {@code true}
   */
  public boolean alertUsage() {
    LocalDate month = SeoulTime.today(clock).withDayOfMonth(1);
    return budget
        .usage(month)
        .filter(u -> (u.reserved() + u.spent()) * 100 >= u.limit() * USAGE_ALERT_PERCENT)
        .map(
            u ->
                jobs.enqueue(
                    JobStore.OPERATOR_ALERT,
                    "budget-threshold:" + month,
                    "{\"kind\":\""
                        + USAGE_ALERT_KIND
                        + "\",\"month\":\""
                        + month.toString().substring(0, 7)
                        + "\",\"used\":"
                        + (u.reserved() + u.spent())
                        + ",\"limit\":"
                        + u.limit()
                        + "}",
                    clock.instant()))
        .orElse(false);
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
