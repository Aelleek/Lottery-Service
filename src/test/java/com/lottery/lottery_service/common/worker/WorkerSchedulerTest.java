package com.lottery.lottery_service.common.worker;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lottery.lottery_service.common.alert.ExpiryWatch;
import com.lottery.lottery_service.common.job.JobRunner;
import com.lottery.lottery_service.common.job.JobState;
import com.lottery.lottery_service.common.job.JobStore;
import com.lottery.lottery_service.common.web.SecuritySignals;
import com.lottery.lottery_service.notification.RoundJobPlanner;
import com.lottery.lottery_service.notification.UnknownMessageReconciler;
import com.lottery.lottery_service.notification.UnknownMessageSweep;
import com.lottery.lottery_service.notification.budget.BudgetReconciler;
import com.lottery.lottery_service.notification.verify.VerificationMessageStore;
import com.lottery.lottery_service.notification.verify.VerificationStore;
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
  private final UnknownMessageReconciler unknown = mock(UnknownMessageReconciler.class);
  private final UnknownMessageSweep sweep = mock(UnknownMessageSweep.class);
  private final VerificationMessageStore verificationMessages =
      mock(VerificationMessageStore.class);
  private final VerificationStore verifications = mock(VerificationStore.class);
  private final ExpiryWatch expiry = mock(ExpiryWatch.class);
  private final SecuritySignals signals = new SecuritySignals();
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
        unknown,
        sweep,
        verificationMessages,
        verifications,
        expiry,
        signals,
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
    verify(housekeeping).deleteExpiredCommandOperations(10_000);
    verify(housekeeping).deleteExpiredAuditEvents(10_000);
    verify(housekeeping).deleteEmptyGuestSubjects(1_000);
    verify(housekeeping).clearMessageCopies(10_000);
    verify(housekeeping).clearChallengeTargets(10_000);
    verify(housekeeping).deleteAggregatedUsageEvents(10_000);
    verify(maintenance).closeNotificationPlans();
    verify(maintenance).cleanupRecords(WorkerScheduler.RECORD_CLEANUP_BATCH);
    verify(maintenance).alertDelayedCleanup();
    verify(budget).reconcile();
    verify(expiry).check();
    scheduler.watchDelivery();
    scheduler.openBudgetMonth();
    verify(budget).openMonth();
    verify(budget).alertUsage();
    verify(unknown).alertLongUnresolved();
  }

  @Test
  @DisplayName("상한이 있는 매일 정리는 묶음이 가득 차면 남은 것이 없을 때까지 다시 하고, 덜 차면 멈춘다(2026-11-09 Q24)")
  void housekeepingRepeatsFullBatches() {
    when(housekeeping.deleteExpiredCommandOperations(10_000)).thenReturn(10_000, 10_000, 3);
    when(housekeeping.deleteEmptyGuestSubjects(1_000)).thenReturn(1_000, 0);
    when(housekeeping.clearMessageCopies(10_000)).thenReturn(10_000);

    scheduler().dailyHousekeeping();

    verify(housekeeping, times(3)).deleteExpiredCommandOperations(10_000);
    verify(housekeeping, times(1)).deleteExpiredAuditEvents(10_000);
    verify(housekeeping, times(2)).deleteEmptyGuestSubjects(1_000);
    verify(housekeeping, times(WorkerScheduler.MAX_HOUSEKEEPING_BATCHES))
        .clearMessageCopies(10_000);
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

  @Test
  @DisplayName("접수 대조는 기한 지난 인증 요청을 만료시키고 멈춘 인증 메시지를 넘긴 뒤 제공자 조회 제한 안의 건수로 UNKNOWN 메시지를 조회한다")
  void reconcilesUnknownMessages() {
    when(verificationMessages.markStaleInFlightUnknown()).thenReturn(2);

    scheduler().reconcileUnknownMessages();

    verify(verifications).expireDue(WorkerScheduler.MAX_EXPIRED_CHALLENGES);
    verify(verificationMessages).markStaleInFlightUnknown();
    verify(sweep).run(UnknownMessageSweep.MAX_LOOKUPS);
  }

  @Test
  @DisplayName("보안 신호는 10분 동안 한 종류가 1,000건을 넘을 때만(1,001건부터) 종류·건수·많이 걸린 경로로 알리고, 센 값은 비운다")
  void alertsSecuritySignalsOverThreshold() {
    for (int i = 0; i < 1_001; i++) {
      signals.record(
          SecuritySignals.Kind.CSRF_REJECTED, "/api/v1/me/lotto/records/" + i + "/cancel-purchase");
    }
    for (int i = 0; i < 1_000; i++) {
      signals.record(SecuritySignals.Kind.TOO_MANY_REQUESTS, "/api/v1/lotto/check");
    }

    scheduler().alertSecuritySignals();

    verify(jobs)
        .enqueue(
            JobStore.OPERATOR_ALERT,
            "security-signal:CSRF_REJECTED:2026-11-14T12:00:00Z",
            "{\"kind\":\"SECURITY_SIGNAL\",\"signal\":\"CSRF_REJECTED\",\"count\":1001,"
                + "\"topPath\":\"/api/v1/me/lotto/records/{id}/cancel-purchase\"}",
            clock.instant());
    verify(jobs, times(1)).enqueue(any(), any(), any(), any());
    org.assertj.core.api.Assertions.assertThat(signals.drain()).isEmpty();
  }

  @Test
  @DisplayName("보안 신호 알림 등록이 실패하면 그 합계를 다음 창으로 되돌려 다시 알릴 수 있다")
  void keepsSecuritySignalsWhenAlertFails() {
    for (int i = 0; i < 1_001; i++) {
      signals.record(SecuritySignals.Kind.ORIGIN_REJECTED, "/api/v1/command-tokens");
    }
    when(jobs.enqueue(any(), any(), any(), any())).thenThrow(new IllegalStateException("db down"));

    scheduler().alertSecuritySignals();

    org.assertj.core.api.Assertions.assertThat(signals.drain())
        .containsEntry(
            SecuritySignals.Kind.ORIGIN_REJECTED,
            new SecuritySignals.Summary(1_001, "/api/v1/command-tokens"));
  }
}
