package com.lottery.lottery_service.common.worker;

import com.lottery.lottery_service.common.job.JobStore;
import com.lottery.lottery_service.common.job.RecoveredJob;
import com.lottery.lottery_service.round.RoundStore;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 앱 안의 주기 작업(07 1절). {@code lottery.worker.enabled=true}일 때만 켠다. 단계 3에서는 job 회수와 마감 봉인만 있고, 다른 주기
 * 작업은 그 기능을 만드는 단계에서 더한다.
 *
 * <ul>
 *   <li>job 회수: 1분마다 lease가 만료된 job을 RETRY_WAIT·DEAD로 돌린다(C-21).
 *   <li>마감 봉인: 30초마다 판매 마감이 지난 회차를 봉인한다(05 5절).
 * </ul>
 */
@Slf4j
@Component
@EnableScheduling
@ConditionalOnProperty(prefix = "lottery.worker", name = "enabled", havingValue = "true")
public class WorkerScheduler {

  private final JobStore jobs;
  private final RoundStore rounds;

  /** 스케줄러를 만든다. */
  public WorkerScheduler(JobStore jobs, RoundStore rounds) {
    this.jobs = jobs;
    this.rounds = rounds;
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
}
