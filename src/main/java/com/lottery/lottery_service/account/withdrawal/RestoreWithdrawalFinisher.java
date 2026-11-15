package com.lottery.lottery_service.account.withdrawal;

import com.lottery.lottery_service.common.job.JobRunner;
import com.lottery.lottery_service.common.job.JobStore;
import java.time.Clock;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 복원 명령 안에서 탈퇴 삭제 job을 끝까지 돌린다(07 4절 3번 "다시 적용한 뒤 연다"). 복원 명령은 주기 작업을 켜지 않으므로, 여기서 돌리지 않으면 되살아난 탈퇴
 * 회원의 자료가 서비스를 연 뒤에야 지워진다.
 */
@Component
public class RestoreWithdrawalFinisher {

  /** 한 번에 돌리는 job 수 상한. 고장으로 끝나지 않는 것을 막는다. */
  static final int MAX_JOBS = 100_000;

  private final JdbcTemplate jdbc;
  private final JobRunner runner;

  /** 구성 요소로 만든다. */
  public RestoreWithdrawalFinisher(
      JdbcTemplate jdbc,
      JobStore jobs,
      WithdrawalJobHandler handler,
      PlatformTransactionManager transactionManager,
      Clock clock) {
    this.jdbc = jdbc;
    this.runner = new JobRunner(jobs, List.of(handler), transactionManager, clock);
  }

  /**
   * 지금 돌릴 수 있는 탈퇴 삭제 job을 모두 돌린다.
   *
   * @return 아직 끝나지 않은 탈퇴 삭제 job 수. 0이 아니면 서비스를 열지 않는다
   */
  public int finish() {
    int ran = 0;
    while (ran < MAX_JOBS && runner.runOnce().isPresent()) {
      ran++;
    }
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM job WHERE job_type = ? AND state <> 'SUCCEEDED'",
        Integer.class,
        WithdrawalService.JOB);
  }
}
