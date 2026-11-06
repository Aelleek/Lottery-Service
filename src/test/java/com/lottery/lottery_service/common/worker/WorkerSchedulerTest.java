package com.lottery.lottery_service.common.worker;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lottery.lottery_service.common.job.JobRunner;
import com.lottery.lottery_service.common.job.JobState;
import com.lottery.lottery_service.common.job.JobStore;
import com.lottery.lottery_service.notification.RoundJobPlanner;
import com.lottery.lottery_service.notification.budget.BudgetReconciler;
import com.lottery.lottery_service.record.HousekeepingCleaner;
import com.lottery.lottery_service.record.RoundMaintenance;
import com.lottery.lottery_service.result.evaluate.EvaluationReconciler;
import com.lottery.lottery_service.result.source.ResultCollectJobHandler;
import com.lottery.lottery_service.round.RoundStore;
import com.lottery.lottery_service.round.domain.RoundCalendar;
import com.lottery.lottery_service.round.domain.RoundSchedule;
import com.lottery.lottery_service.stats.AggregationPlanner;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

class WorkerSchedulerTest {

  private final JobRunner runner = mock(JobRunner.class);
  private final JobStore jobs = mock(JobStore.class);
  private final RoundStore rounds = mock(RoundStore.class);
  private final EvaluationReconciler reconciler = mock(EvaluationReconciler.class);
  private final HousekeepingCleaner housekeeping = mock(HousekeepingCleaner.class);
  private final PlatformTransactionManager tm = mock(PlatformTransactionManager.class);
  private final RoundJobPlanner planner = mock(RoundJobPlanner.class);
  private final ResultCollectJobHandler collect = mock(ResultCollectJobHandler.class);
  private final AggregationPlanner aggregation = mock(AggregationPlanner.class);
  private final RoundMaintenance maintenance = mock(RoundMaintenance.class);
  private final BudgetReconciler budget = mock(BudgetReconciler.class);
  private final Clock clock = Clock.fixed(Instant.parse("2026-11-14T12:00:00Z"), ZoneOffset.UTC);

  private WorkerScheduler scheduler() {
    TransactionStatus status = new SimpleTransactionStatus();
    when(tm.getTransaction(any())).thenReturn(status);
    return new WorkerScheduler(
        runner,
        jobs,
        rounds,
        reconciler,
        housekeeping,
        planner,
        collect,
        aggregation,
        maintenance,
        budget,
        clock,
        tm);
  }

  @Test
  @DisplayName("job 실행은 처리할 job이 없을 때까지 이어서 처리한다")
  void runsUntilEmpty() {
    when(runner.runOnce())
        .thenReturn(
            Optional.of(JobState.SUCCEEDED), Optional.of(JobState.SUCCEEDED), Optional.empty());

    scheduler().runJobs();

    verify(runner, times(3)).runOnce();
  }

  @Test
  @DisplayName("회수, 봉인, 대조, 정리는 각 저장소를 부른다")
  void delegates() {
    WorkerScheduler scheduler = scheduler();

    scheduler.recoverJobs();
    scheduler.sealRounds();
    scheduler.reconcileEvaluations();
    scheduler.dailyHousekeeping();

    verify(jobs).recoverExpired();
    verify(rounds).sealDue();
    verify(reconciler).enqueueMissing(1_000);
    verify(housekeeping).deleteFinishedJobs();
    verify(housekeeping).deleteExpiredGuestCredentials();
    verify(housekeeping).deleteEmptyGuestSubjects(1_000);
    verify(maintenance).closeNotificationPlans();
    verify(maintenance).cleanupRecords(WorkerScheduler.RECORD_CLEANUP_BATCH);
    verify(maintenance).alertDelayedCleanup();
    verify(budget).reconcile();
  }

  @Test
  @DisplayName("추첨이 하루 안팎인 회차만 슬롯과 수집을 계획한다")
  void plansNearRounds() {
    RoundSchedule near =
        new RoundSchedule(
            1200,
            Instant.parse("2026-11-07T21:00:00Z"),
            Instant.parse("2026-11-14T11:00:00Z"),
            Instant.parse("2026-11-14T11:35:00Z"),
            1);
    RoundSchedule far =
        new RoundSchedule(
            1201,
            Instant.parse("2026-11-14T21:00:00Z"),
            Instant.parse("2026-11-21T11:00:00Z"),
            Instant.parse("2026-11-21T11:35:00Z"),
            1);
    when(rounds.calendar()).thenReturn(new RoundCalendar(List.of(near, far)));

    scheduler().planRounds();

    verify(planner).planSlots(near);
    verify(planner, never()).planSlots(far);
    verify(collect).plan(1200, near.drawAt());
  }
}
