package com.lottery.lottery_service.common.job;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * job 하나를 가져와 끝까지 처리한다. 걸음마다 한 트랜잭션에서 처리기의 쓰기, lease 확인, 진행 위치 저장을 함께 커밋한다. 취소 요청이 있으면 다음 걸음 전에
 * 멈춘다. 실패하면 그 걸음은 rollback되고 {@link JobStore#fail}로 다시 시도 시각을 정한다.
 */
@Slf4j
@Component
public class JobRunner {

  /** 실패 뒤 다시 시도까지의 기본 대기. */
  static final Duration RETRY_DELAY = Duration.ofMinutes(1);

  private final JobStore jobs;
  private final Map<String, JobHandler> handlers;
  private final TransactionTemplate tx;
  private final Clock clock;

  /** 등록된 처리기로 실행기를 만든다. */
  public JobRunner(
      JobStore jobs,
      List<JobHandler> handlers,
      PlatformTransactionManager transactionManager,
      Clock clock) {
    this.jobs = jobs;
    this.handlers =
        handlers.stream().collect(Collectors.toMap(JobHandler::type, Function.identity()));
    this.tx = new TransactionTemplate(transactionManager);
    this.clock = clock;
  }

  /**
   * 처리할 job이 있으면 하나를 끝까지 처리한다.
   *
   * @return 처리한 job이 있으면 그 상태, 없으면 빈 값
   */
  public Optional<JobState> runOnce() {
    Optional<JobLease> claimed = jobs.claim(List.copyOf(handlers.keySet()));
    if (claimed.isEmpty()) {
      return Optional.empty();
    }
    JobLease lease = claimed.get();
    JobHandler handler = handlers.get(lease.type());
    String cursor = lease.cursor();
    try {
      while (true) {
        if (jobs.cancelIfRequested(lease)) {
          return Optional.of(JobState.CANCELED);
        }
        String current = cursor;
        JobStep step;
        if (handler.transactional()) {
          step = tx.execute(status -> save(lease, handler.step(lease, current)));
        } else {
          JobStep done = handler.step(lease, current);
          step = tx.execute(status -> save(lease, done));
        }
        if (step instanceof JobStep.Done) {
          return Optional.of(JobState.SUCCEEDED);
        }
        cursor = ((JobStep.Continue) step).cursorJson();
      }
    } catch (LeaseLostException e) {
      log.warn("job lease lost id={}", lease.id());
      return Optional.empty();
    } catch (RuntimeException e) {
      log.warn("job failed id={} type={}", lease.id(), lease.type(), e);
      return Optional.of(
          jobs.fail(lease, e.getClass().getSimpleName(), clock.instant().plus(RETRY_DELAY)));
    }
  }

  private JobStep save(JobLease lease, JobStep step) {
    if (step instanceof JobStep.Continue c) {
      jobs.saveProgress(lease, c.cursorJson());
    } else {
      jobs.complete(lease);
    }
    return step;
  }
}
