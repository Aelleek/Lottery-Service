package com.lottery.lottery_service.notification;

import static com.lottery.lottery_service.common.db.DbValues.instant;

import com.lottery.lottery_service.common.error.ExceptionLogs;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * UNKNOWN 메시지를 ID 순서로 돌아가며 제공자에 조회하고 결과를 {@link UnknownMessageReconciler}로 반영한다(05 8.3절). 조회는 트랜잭션
 * 밖에서, 반영은 메시지마다 따로 트랜잭션에서 한다. 한 번에 조회하는 건수는 제공자 조회 사용량 제한(5초에 20회) 안으로 둔다.
 */
@Slf4j
@Component
public class UnknownMessageSweep {

  /** 한 번에 조회하는 최대 건수. */
  public static final int MAX_LOOKUPS = 20;

  private final JdbcTemplate jdbc;
  private final MessageLookup lookup;
  private final UnknownMessageReconciler reconciler;
  private final TransactionTemplate tx;
  private long afterId;

  /** 대조 순회기를 만든다. */
  public UnknownMessageSweep(
      JdbcTemplate jdbc,
      MessageLookup lookup,
      UnknownMessageReconciler reconciler,
      PlatformTransactionManager transactionManager) {
    this.jdbc = jdbc;
    this.lookup = lookup;
    this.reconciler = reconciler;
    this.tx = new TransactionTemplate(transactionManager);
  }

  /**
   * UNKNOWN 메시지를 최대 {@code limit}건 조회하고 반영한다. 조회가 예외로 끝나면 찾지 못한 것으로 보고, 그것만으로 미접수로 확정하지 않는다. 한 메시지의
   * 반영 실패는 다른 메시지를 막지 않는다. 대조 기간이 지나 끝낸 메시지는 마지막에 회차마다 운영자 알림 1건으로 묶는다.
   *
   * @return 이번에 UNKNOWN을 벗어난 메시지 수
   */
  public synchronized int run(int limit) {
    int batch = Math.min(limit, MAX_LOOKUPS);
    List<MessageLookup.UnknownMessage> due = due(afterId, batch);
    if (due.size() < batch && afterId > 0) {
      afterId = 0;
      List<MessageLookup.UnknownMessage> head = due(0, batch - due.size());
      due = new ArrayList<>(due);
      for (MessageLookup.UnknownMessage m : head) {
        if (due.stream().noneMatch(d -> d.id() == m.id())) {
          due.add(m);
        }
      }
    }
    int resolved = 0;
    List<Long> failed = new ArrayList<>();
    for (MessageLookup.UnknownMessage message : due) {
      afterId = message.id();
      ProviderStatus status = find(message);
      try {
        String state = tx.execute(s -> reconciler.apply(message.id(), status));
        if (!"UNKNOWN".equals(state)) {
          resolved++;
        }
        if ("FAILED".equals(state)) {
          failed.add(message.id());
        }
      } catch (RuntimeException e) {
        ExceptionLogs.warn(log, "message reconcile failed messageId={}", e, message.id());
      }
    }
    try {
      tx.execute(s -> reconciler.alertReconcileTimeouts(failed));
    } catch (RuntimeException e) {
      ExceptionLogs.warn(log, "reconcile timeout alert failed count={}", e, failed.size());
    }
    return resolved;
  }

  private List<MessageLookup.UnknownMessage> due(long after, int batch) {
    return jdbc.query(
        "SELECT id, provider_request_key, provider_message_id, last_attempt_at"
            + " FROM delivery_message WHERE state = 'UNKNOWN' AND id > ?"
            + " ORDER BY id LIMIT ?",
        (rs, i) ->
            new MessageLookup.UnknownMessage(
                rs.getLong("id"),
                rs.getBytes("provider_request_key"),
                rs.getString("provider_message_id"),
                instant(rs.getObject("last_attempt_at", LocalDateTime.class))),
        after,
        batch);
  }

  private ProviderStatus find(MessageLookup.UnknownMessage message) {
    try {
      ProviderStatus status = lookup.lookup(message);
      return status == null ? new ProviderStatus.NotFound() : status;
    } catch (RuntimeException e) {
      ExceptionLogs.warn(log, "message lookup failed messageId={}", e, message.id());
      return new ProviderStatus.NotFound();
    }
  }
}
