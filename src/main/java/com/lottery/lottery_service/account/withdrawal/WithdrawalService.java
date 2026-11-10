package com.lottery.lottery_service.account.withdrawal;

import com.lottery.lottery_service.common.error.BusinessException;
import com.lottery.lottery_service.common.error.ErrorCode;
import com.lottery.lottery_service.common.job.JobStore;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 탈퇴를 접수한다(05 11절, 04 6.2절 1번). 회원과 회원 주체를 WITHDRAWING으로 바꾸고 {@code auth_version}을 올려 세션을 무효로 하며,
 * 대기 중인 메시지를 SUPPRESSED로 바꾸고, 같은 트랜잭션에 삭제 job을 등록한다. 재인증 확인은 호출한 쪽에서 한다.
 */
@Component
public class WithdrawalService {

  /** 탈퇴 삭제 job 종류. */
  public static final String JOB = "WITHDRAWAL_DELETE";

  private final JdbcTemplate jdbc;
  private final TransactionTemplate tx;
  private final JobStore jobs;
  private final Clock clock;

  /** 서비스를 만든다. */
  public WithdrawalService(
      JdbcTemplate jdbc,
      PlatformTransactionManager transactionManager,
      JobStore jobs,
      Clock clock) {
    this.jdbc = jdbc;
    this.tx = new TransactionTemplate(transactionManager);
    this.jobs = jobs;
    this.clock = clock;
  }

  /**
   * 탈퇴를 접수하고 접근을 막는다. 이미 접수됐으면 아무것도 바꾸지 않는다.
   *
   * @throws BusinessException 회원이 없으면 {@code NOT_FOUND}
   */
  public void block(long memberId) {
    tx.executeWithoutResult(
        status -> {
          List<Map<String, Object>> subjects =
              jdbc.queryForList(
                  "SELECT s.id, m.state FROM actor_subject s JOIN member m ON m.id = s.member_id"
                      + " WHERE s.member_id = ? FOR UPDATE",
                  memberId);
          if (subjects.isEmpty()) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "요청한 내용을 찾을 수 없습니다.");
          }
          if (!"ACTIVE".equals(subjects.get(0).get("state"))) {
            return;
          }
          long subjectId = ((Number) subjects.get(0).get("id")).longValue();
          jdbc.update(
              "UPDATE member SET state = 'WITHDRAWING', auth_version = auth_version + 1,"
                  + " last_login_at = NULL WHERE id = ?",
              memberId);
          jdbc.update("UPDATE actor_subject SET state = 'WITHDRAWING' WHERE id = ?", subjectId);
          jdbc.update(
              "UPDATE delivery_message SET state = 'SUPPRESSED', version = version + 1"
                  + " WHERE member_id = ? AND state IN ('PENDING','RETRY_WAIT','HALTED')",
              memberId);
          jobs.enqueue(
              JOB, "member:" + memberId, "{\"memberId\":" + memberId + "}", clock.instant());
        });
  }
}
