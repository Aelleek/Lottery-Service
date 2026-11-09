package com.lottery.lottery_service.common.worker;

import com.lottery.lottery_service.common.alert.ExpiryWatch;
import com.lottery.lottery_service.common.job.JobRunner;
import com.lottery.lottery_service.common.job.JobStore;
import com.lottery.lottery_service.common.job.RecoveredJob;
import com.lottery.lottery_service.notification.RoundJobPlanner;
import com.lottery.lottery_service.notification.UnknownMessageReconciler;
import com.lottery.lottery_service.notification.UnknownMessageSweep;
import com.lottery.lottery_service.notification.budget.BudgetReconciler;
import com.lottery.lottery_service.record.HousekeepingCleaner;
import com.lottery.lottery_service.record.RoundMaintenance;
import com.lottery.lottery_service.result.evaluate.EvaluationReconciler;
import com.lottery.lottery_service.result.source.ResultCollectJobHandler;
import com.lottery.lottery_service.round.RoundStore;
import com.lottery.lottery_service.round.domain.RoundSchedule;
import com.lottery.lottery_service.stats.AggregationPlanner;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 앱 안의 주기 작업(07 1절). {@code lottery.worker.enabled=true}일 때만 켠다. 앱이 새 스키마로 전환되기 전에는 job 테이블이 없어서
 * 기본은 꺼져 있다.
 *
 * <ul>
 *   <li>job 실행: 처리할 job을 차례로 끝까지 처리한다.
 *   <li>job 회수: 1분마다 lease가 만료된 job을 RETRY_WAIT·DEAD로 돌린다.
 *   <li>마감 봉인: 판매 마감이 지난 회차를 봉인한다.
 *   <li>회차 계획: 10분마다 추첨이 하루 안팎인 회차의 발송 슬롯 job과 첫 결과 수집 job을 등록한다(중복 키로 한 번만).
 *   <li>미평가 대조: 매시간 판정이 빠진 기록에 판정 job을 등록한다.
 *   <li>집계: 매시간 최근 기간을 다시 계산하고 보정 기간이 끝난 기간을 확정한다.
 *   <li>접수 대조: 1분마다 접수 여부를 모르는 메시지를 최대 20건 제공자에 조회해 반영한다.
 *   <li>발송 감시: 매시간 예산 80% 도달과 24시간 넘은 접수 확인을 운영자에게 알린다.
 *   <li>예산 월 전환: 매월 1일 00:00(서울) 그 달 예산 행을 만든다.
 *   <li>보관 정리: 매일 04:00(서울) 끝난 job, 만료된 비회원 자격, 빈 비회원 주체를 정리하고, 알림 계획 완료를 확인하고, 보관 기간이 지난 기록을 지우고,
 *       정리 지연과 예산 카운터 불일치, 인증서·연동 만료를 알린다.
 * </ul>
 */
@Slf4j
@Component
@EnableScheduling
@ConditionalOnProperty(prefix = "lottery.worker", name = "enabled", havingValue = "true")
public class WorkerScheduler {

  static final int MAX_JOBS_PER_TICK = 100;

  static final int RECORD_CLEANUP_BATCH = 500;

  private final JobRunner runner;
  private final JobStore jobs;
  private final RoundStore rounds;
  private final EvaluationReconciler reconciler;
  private final HousekeepingCleaner housekeeping;
  private final RoundJobPlanner planner;
  private final ResultCollectJobHandler collect;
  private final AggregationPlanner aggregation;
  private final RoundMaintenance maintenance;
  private final BudgetReconciler budget;
  private final UnknownMessageReconciler unknown;
  private final UnknownMessageSweep sweep;
  private final ExpiryWatch expiry;
  private final Clock clock;
  private final TransactionTemplate tx;

  /** 스케줄러를 만든다. */
  public WorkerScheduler(
      JobRunner runner,
      JobStore jobs,
      RoundStore rounds,
      EvaluationReconciler reconciler,
      HousekeepingCleaner housekeeping,
      RoundJobPlanner planner,
      ResultCollectJobHandler collect,
      AggregationPlanner aggregation,
      RoundMaintenance maintenance,
      BudgetReconciler budget,
      UnknownMessageReconciler unknown,
      UnknownMessageSweep sweep,
      ExpiryWatch expiry,
      Clock clock,
      PlatformTransactionManager transactionManager) {
    this.runner = runner;
    this.jobs = jobs;
    this.rounds = rounds;
    this.reconciler = reconciler;
    this.housekeeping = housekeeping;
    this.planner = planner;
    this.collect = collect;
    this.aggregation = aggregation;
    this.maintenance = maintenance;
    this.budget = budget;
    this.unknown = unknown;
    this.sweep = sweep;
    this.expiry = expiry;
    this.clock = clock;
    this.tx = new TransactionTemplate(transactionManager);
  }

  /** 처리할 job을 차례로 처리한다. */
  @Scheduled(fixedDelayString = "${lottery.worker.job-poll-ms:5000}")
  public void runJobs() {
    int processed = 0;
    while (processed < MAX_JOBS_PER_TICK && runner.runOnce().isPresent()) {
      processed++;
    }
  }

  /** lease가 만료된 job을 회수한다. */
  @Scheduled(fixedDelay = 60_000)
  public void recoverJobs() {
    List<RecoveredJob> recovered = jobs.recoverExpired();
    if (!recovered.isEmpty()) {
      log.warn("recovered expired jobs count={}", recovered.size());
    }
  }

  /** 판매 마감이 지난 회차를 봉인한다. */
  @Scheduled(fixedDelay = 30_000)
  public void sealRounds() {
    rounds.sealDue();
  }

  /** 추첨이 하루 안팎인 회차의 슬롯·수집 job을 등록한다. */
  @Scheduled(fixedDelay = 600_000)
  public void planRounds() {
    Instant now = clock.instant();
    for (RoundSchedule schedule : rounds.calendar().schedules().values()) {
      if (schedule.drawAt().isAfter(now.minus(Duration.ofDays(1)))
          && schedule.drawAt().isBefore(now.plus(Duration.ofDays(1)))) {
        tx.executeWithoutResult(
            status -> {
              planner.planSlots(schedule);
              collect.plan(schedule.roundNo(), schedule.drawAt());
            });
      }
    }
  }

  /** 판정이 빠진 기록을 찾는다. */
  @Scheduled(cron = "0 0 * * * *", zone = "Asia/Seoul")
  public void reconcileEvaluations() {
    tx.execute(status -> reconciler.enqueueMissing(1_000));
  }

  /** 최근 기간을 다시 집계하고 보정 기간이 끝난 기간을 확정한다. */
  @Scheduled(cron = "0 10 * * * *", zone = "Asia/Seoul")
  public void aggregateUsage() {
    Integer verified = tx.execute(status -> aggregation.run());
    if (verified != null && verified > 0) {
      log.info("verified usage aggregates periods={}", verified);
    }
  }

  /** 접수 여부를 모르는 메시지를 제공자에 조회해 반영한다. */
  @Scheduled(fixedDelay = 60_000)
  public void reconcileUnknownMessages() {
    int resolved = sweep.run(UnknownMessageSweep.MAX_LOOKUPS);
    if (resolved > 0) {
      log.info("resolved unknown messages count={}", resolved);
    }
  }

  /** 예산 80% 도달과 오래 풀리지 않은 접수 확인을 알린다. */
  @Scheduled(cron = "0 20 * * * *", zone = "Asia/Seoul")
  public void watchDelivery() {
    tx.executeWithoutResult(
        status -> {
          budget.alertUsage();
          unknown.alertLongUnresolved();
        });
  }

  /** 새 달 예산 행을 만든다. */
  @Scheduled(cron = "0 0 0 1 * *", zone = "Asia/Seoul")
  public void openBudgetMonth() {
    budget.openMonth();
  }

  /** 매일 보관 정리. */
  @Scheduled(cron = "0 0 4 * * *", zone = "Asia/Seoul")
  public void dailyHousekeeping() {
    tx.executeWithoutResult(
        status -> {
          housekeeping.deleteFinishedJobs();
          housekeeping.deleteExpiredGuestCredentials();
          housekeeping.deleteExpiredCommandOperations(10_000);
          housekeeping.deleteEmptyGuestSubjects(1_000);
          housekeeping.clearMessageCopies(10_000);
          housekeeping.deleteAggregatedUsageEvents(10_000);
        });
    int closed = maintenance.closeNotificationPlans();
    int deleted = maintenance.cleanupRecords(RECORD_CLEANUP_BATCH);
    Integer delayed = tx.execute(status -> maintenance.alertDelayedCleanup());
    tx.executeWithoutResult(status -> budget.reconcile());
    tx.executeWithoutResult(status -> expiry.check());
    log.info(
        "daily cleanup closedPlans={} deletedRecords={} delayedAlerts={}",
        closed,
        deleted,
        delayed);
  }
}
