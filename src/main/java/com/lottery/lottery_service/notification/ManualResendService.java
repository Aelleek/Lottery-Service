package com.lottery.lottery_service.notification;

import static com.lottery.lottery_service.common.db.DbValues.instant;

import com.lottery.lottery_service.common.error.BusinessException;
import com.lottery.lottery_service.common.error.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 수동 다시 보내기(05 8.6절, D-031). 결과 게시 후 7일 안에서만 할 수 있다. HALTED·RETRY_WAIT 메시지를 PENDING으로 되돌리고 슬롯 실행과
 * 같은 절차를 한 번 돈다. 접수됐거나(ACCEPTED·DELIVERED) 접수 확인 중(UNKNOWN)인 메시지와 SUPPRESSED는 건드리지 않는다. 권한·다중 요소
 * 인증·사유 감사는 호출한 쪽에서 한다.
 */
@Component
public class ManualResendService {

  private final JdbcTemplate jdbc;
  private final ResultSlotRunner runner;
  private final TransactionTemplate tx;
  private final Clock clock;

  /** 서비스를 만든다. */
  public ManualResendService(
      JdbcTemplate jdbc,
      ResultSlotRunner runner,
      PlatformTransactionManager transactionManager,
      Clock clock) {
    this.jdbc = jdbc;
    this.runner = runner;
    this.tx = new TransactionTemplate(transactionManager);
    this.clock = clock;
  }

  /**
   * 다시 보낸다.
   *
   * @throws BusinessException 결과가 게시되지 않았거나 기한이 지났으면 {@code DISPATCH_WINDOW_CLOSED}
   */
  public SlotResult resend(int roundNo) {
    Instant now = clock.instant();
    List<LocalDateTime> published =
        jdbc.queryForList(
            "SELECT d.published_at FROM lotto_round r JOIN draw_result d ON d.id = r.published_result_id"
                + " WHERE r.round_no = ?",
            LocalDateTime.class,
            roundNo);
    if (published.isEmpty()
        || !now.isBefore(instant(published.get(0)).plus(ResultMessageStore.VALIDITY))) {
      throw new BusinessException(ErrorCode.DISPATCH_WINDOW_CLOSED, "다시 보내기 기한이 지났습니다.");
    }
    tx.executeWithoutResult(
        status ->
            jdbc.update(
                "UPDATE delivery_message SET state = 'PENDING', version = version + 1"
                    + " WHERE round_no = ? AND purpose = 'RESULT' AND state IN ('HALTED','RETRY_WAIT')",
                roundNo));
    return runner.run(roundNo, new ResultSlot(now, false, false));
  }
}
