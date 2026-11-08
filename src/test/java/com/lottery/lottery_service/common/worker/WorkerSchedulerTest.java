package com.lottery.lottery_service.common.worker;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.lottery.lottery_service.common.job.JobStore;
import com.lottery.lottery_service.round.RoundStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class WorkerSchedulerTest {

  private final JobStore jobs = mock(JobStore.class);
  private final RoundStore rounds = mock(RoundStore.class);
  private final WorkerScheduler scheduler = new WorkerScheduler(jobs, rounds);

  @Test
  @DisplayName("주기 작업이 job 회수와 마감 봉인을 부른다")
  void callsRecoveryAndSeal() {
    scheduler.recoverJobs();
    scheduler.sealRounds();

    verify(jobs).recoverExpired();
    verify(rounds).sealDue();
  }
}
