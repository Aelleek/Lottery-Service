package com.lottery.lottery_service.notification;

import com.lottery.lottery_service.common.job.JobStore;
import java.time.Clock;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 결과 알림 슬롯 하나를 실행한다(05 8.2절). 메시지마다 짧은 트랜잭션으로 직전 확인과 IN_FLIGHT 예약을 커밋한 뒤 트랜잭션 밖에서 제공자를 부르고, 결과를 다시
 * 짧은 트랜잭션으로 남긴다. 한 회원의 오류가 다른 회원의 발송을 막지 않는다. 결과 수집은 이 단위 밖에서 미리 시도한다.
 */
@Slf4j
@Component
public class ResultSlotRunner {

  /** 결과 알림 1건의 비용(0.1원 단위). 13원+VAT. */
  static final long COST_UNITS = 143;

  private final JdbcTemplate jdbc;
  private final ResultMessageStore messages;
  private final MessageSender sender;
  private final JobStore jobs;
  private final TransactionTemplate tx;
  private final Clock clock;

  /** 실행기를 만든다. */
  public ResultSlotRunner(
      JdbcTemplate jdbc,
      ResultMessageStore messages,
      MessageSender sender,
      JobStore jobs,
      PlatformTransactionManager transactionManager,
      Clock clock) {
    this.jdbc = jdbc;
    this.messages = messages;
    this.sender = sender;
    this.jobs = jobs;
    this.tx = new TransactionTemplate(transactionManager);
    this.clock = clock;
  }

  /** 슬롯을 실행하고 결과를 돌려준다. 운영자 알림 대상 슬롯이 실패하면 운영자 알림 job을 등록하고, 마지막 슬롯이 실패하면 남은 메시지를 멈춘다. */
  public SlotResult run(int roundNo, ResultSlot slot) {
    SlotResult result = execute(roundNo);
    if (!result.success()) {
      tx.executeWithoutResult(
          status -> {
            if (slot.alertOnFailure()) {
              jobs.enqueue(
                  JobStore.OPERATOR_ALERT,
                  "slot-failed:" + roundNo + ":" + slot.at(),
                  "{\"kind\":\"SLOT_FAILED\",\"roundNo\":"
                      + roundNo
                      + ",\"cause\":\""
                      + result.cause()
                      + "\",\"failed\":"
                      + result.failed()
                      + ",\"unknown\":"
                      + result.unknown()
                      + ",\"last\":"
                      + slot.last()
                      + "}",
                  clock.instant());
            }
            if (slot.last()) {
              messages.haltRemaining(roundNo);
            }
          });
    }
    return result;
  }

  private SlotResult execute(int roundNo) {
    Long published =
        jdbc.queryForObject(
            "SELECT published_result_id FROM lotto_round WHERE round_no = ?", Long.class, roundNo);
    if (published == null) {
      return new SlotResult(false, "RESULT_NOT_PUBLISHED", 0, 0);
    }
    if (evaluationPending(roundNo)) {
      return new SlotResult(false, "EVALUATION_PENDING", 0, 0);
    }
    tx.execute(status -> messages.createMissing(roundNo));
    int failed = 0;
    boolean budgetShort = false;
    for (long id : messages.sendable(roundNo)) {
      Boolean ready;
      try {
        ready = tx.execute(status -> messages.recheck(id) && messages.startSending(id, COST_UNITS));
      } catch (RuntimeException e) {
        log.warn(
            "result message prepare failed message={} cause={}", id, e.getClass().getSimpleName());
        failed++;
        continue;
      }
      if (!Boolean.TRUE.equals(ready)) {
        if (stateOf(id).equals("PENDING") || stateOf(id).equals("RETRY_WAIT")) {
          budgetShort = true;
          failed++;
        }
        continue;
      }
      SendOutcome outcome;
      try {
        outcome = sender.send(id);
      } catch (RuntimeException e) {
        outcome = new SendOutcome.Unknown();
      }
      SendOutcome recorded = outcome;
      tx.executeWithoutResult(status -> messages.recordOutcome(id, recorded));
      if (!(outcome instanceof SendOutcome.Accepted)) {
        failed++;
      }
    }
    int unknown =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM delivery_message WHERE round_no = ? AND purpose = 'RESULT'"
                + " AND state = 'UNKNOWN'",
            Integer.class,
            roundNo);
    if (failed == 0) {
      return new SlotResult(true, null, 0, unknown);
    }
    return new SlotResult(
        false, budgetShort ? "BUDGET_EXHAUSTED" : "PARTIAL_FAILURE", failed, unknown);
  }

  private boolean evaluationPending(int roundNo) {
    List<Integer> pending =
        jdbc.queryForList(
            "SELECT 1 FROM lotto_record l JOIN lotto_round r ON r.round_no = l.round_no"
                + " JOIN actor_subject s ON s.id = l.owner_subject_id AND s.kind = 'MEMBER'"
                + " WHERE l.round_no = ? AND l.deleted_at IS NULL AND l.created_at < r.cutoff_close_at"
                + " AND NOT EXISTS (SELECT 1 FROM winning_evaluation e WHERE e.record_id = l.id"
                + " AND e.result_id = r.published_result_id AND e.record_version = l.version) LIMIT 1",
            Integer.class,
            roundNo);
    return !pending.isEmpty();
  }

  private String stateOf(long id) {
    return jdbc.queryForObject("SELECT state FROM delivery_message WHERE id = ?", String.class, id);
  }
}
