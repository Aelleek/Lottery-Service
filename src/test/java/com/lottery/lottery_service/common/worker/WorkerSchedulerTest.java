package com.lottery.lottery_service.common.worker;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lottery.lottery_service.common.job.JobRunner;
import com.lottery.lottery_service.common.job.JobState;
import com.lottery.lottery_service.common.job.JobStore;
import com.lottery.lottery_service.record.HousekeepingCleaner;
import com.lottery.lottery_service.result.evaluate.EvaluationReconciler;
import com.lottery.lottery_service.round.RoundStore;
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

  private WorkerScheduler scheduler() {
    TransactionStatus status = new SimpleTransactionStatus();
    when(tm.getTransaction(any())).thenReturn(status);
    return new WorkerScheduler(runner, jobs, rounds, reconciler, housekeeping, tm);
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
  }
}
