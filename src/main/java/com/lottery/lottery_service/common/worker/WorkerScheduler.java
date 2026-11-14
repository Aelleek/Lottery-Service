package com.lottery.lottery_service.common.worker;

import com.lottery.lottery_service.common.alert.ExpiryWatch;
import com.lottery.lottery_service.common.job.JobRunner;
import com.lottery.lottery_service.common.job.JobStore;
import com.lottery.lottery_service.common.job.RecoveredJob;
import com.lottery.lottery_service.common.web.SecuritySignals;
import com.lottery.lottery_service.notification.ResultSlotJobHandler;
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
import com.lottery.lottery_service.round.domain.RoundSchedule;
import com.lottery.lottery_service.stats.AggregationPlanner;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.IntUnaryOperator;
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
 *   <li>접수 대조: 10분마다 접수 여부를 모르는 메시지를 최대 20건 제공자에 조회해 반영한다.
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

  /** 한 번에 만료 처리하는 인증 요청 수. */
  static final int MAX_EXPIRED_CHALLENGES = 1_000;

  /** 보안 신호 알림 기준. 10분 동안 한 종류가 이 수를 넘으면 알린다(07 2.1절, 초기 설정값). */
  static final long SECURITY_SIGNAL_THRESHOLD = 1_000;

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
  private final VerificationMessageStore verificationMessages;
  private final VerificationStore verifications;
  private final ExpiryWatch expiry;
  private final SecuritySignals signals;
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
      VerificationMessageStore verificationMessages,
      VerificationStore verifications,
      ExpiryWatch expiry,
      SecuritySignals signals,
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
    this.verificationMessages = verificationMessages;
    this.verifications = verifications;
    this.expiry = expiry;
    this.signals = signals;
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

  /**
   * lease가 만료된 job을 회수한다. 결과 알림 슬롯 job이면 같은 회수 트랜잭션에서 그 회차의 IN_FLIGHT 메시지를 UNKNOWN으로 넘겨 대조에 맡긴다(05
   * 8.3절). 넘기기가 실패하면 회수도 되돌려져 다음 회수에서 다시 한다. 주기 작업은 한 스레드에서 돌아 회수와 다음 job 실행이 겹치지 않는다.
   */
  @Scheduled(fixedDelay = 60_000)
  public void recoverJobs() {
    List<RecoveredJob> recovered =
        jobs.recoverExpired(
            job -> {
              if (ResultSlotJobHandler.JOB.equals(job.type())) {
                int marked = unknown.markInFlightUnknownForSlotJob(job.id());
                log.warn("recovered result slot job={} inFlightToUnknown={}", job.id(), marked);
              }
            });
    if (recovered != null && !recovered.isEmpty()) {
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

  /**
   * 기한이 지난 인증 요청의 코드 HMAC을 지우고, 멈춘 인증 메시지를 UNKNOWN으로 넘긴 뒤, 접수 여부를 모르는 메시지를 제공자에 조회해 반영한다. 기본 10분
   * 간격이다(07 1절, 초기 설정값).
   */
  @Scheduled(fixedDelayString = "${lottery.worker.unknown-sweep-ms:600000}")
  public void reconcileUnknownMessages() {
    tx.executeWithoutResult(status -> verifications.expireDue(MAX_EXPIRED_CHALLENGES));
    Integer stale = tx.execute(status -> verificationMessages.markStaleInFlightUnknown());
    if (stale != null && stale > 0) {
      log.warn("verification messages left in flight count={}", stale);
    }
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

  /**
   * 10분 동안 센 보안 신호(429, CSRF 거절, 출처 검사 거절) 가운데 기준을 넘은 종류를 운영자에게 알린다(07 2.1절). 내용은 종류, 건수, 많이 걸린
   * 경로뿐이고 IP는 넣지 않는다.
   */
  @Scheduled(fixedRate = 600_000, initialDelay = 600_000)
  public void alertSecuritySignals() {
    Instant now = clock.instant();
    Map<SecuritySignals.Kind, SecuritySignals.Summary> drained = signals.drain();
    for (Map.Entry<SecuritySignals.Kind, SecuritySignals.Summary> e : drained.entrySet()) {
      SecuritySignals.Summary summary = e.getValue();
      if (summary.count() <= SECURITY_SIGNAL_THRESHOLD) {
        continue;
      }
      try {
        jobs.enqueue(
            JobStore.OPERATOR_ALERT,
            "security-signal:" + e.getKey() + ":" + now,
            "{\"kind\":\"SECURITY_SIGNAL\",\"signal\":\""
                + e.getKey()
                + "\",\"count\":"
                + summary.count()
                + ",\"topPath\":\""
                + summary.topPath()
                + "\"}",
            now);
      } catch (RuntimeException ex) {
        signals.restore(e.getKey(), summary);
        log.warn(
            "security signal alert failed kind={} cause={}",
            e.getKey(),
            ex.getClass().getSimpleName());
      }
    }
  }

  /** 한 종류 정리가 하루에 도는 최대 묶음 수. */
  static final int MAX_HOUSEKEEPING_BATCHES = 1_000;

  /**
   * 매일 보관 정리. 상한이 있는 정리는 묶음마다 커밋하고, 묶음이 가득 찼으면 남은 것이 없을 때까지 다시 한다(2026-11-09 Q24). 고장으로 끝나지 않는 것을
   * 막으려고 한 종류에 {@value #MAX_HOUSEKEEPING_BATCHES}묶음까지만 돈다.
   */
  @Scheduled(cron = "0 0 4 * * *", zone = "Asia/Seoul")
  public void dailyHousekeeping() {
    tx.executeWithoutResult(
        status -> {
          housekeeping.deleteFinishedJobs();
          housekeeping.deleteExpiredGuestCredentials();
        });
    drain("command-operations", 10_000, housekeeping::deleteExpiredCommandOperations);
    drain("audit-events", 10_000, housekeeping::deleteExpiredAuditEvents);
    drain("guest-subjects", 1_000, housekeeping::deleteEmptyGuestSubjects);
    drain("message-copies", 10_000, housekeeping::clearMessageCopies);
    drain("challenge-targets", 10_000, housekeeping::clearChallengeTargets);
    drain("usage-events", 10_000, housekeeping::deleteAggregatedUsageEvents);
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

  private void drain(String name, int limit, IntUnaryOperator batch) {
    for (int i = 0; i < MAX_HOUSEKEEPING_BATCHES; i++) {
      Integer done = tx.execute(status -> batch.applyAsInt(limit));
      if (done == null || done < limit) {
        return;
      }
    }
    log.warn("daily cleanup stopped at batch limit kind={}", name);
  }
}
