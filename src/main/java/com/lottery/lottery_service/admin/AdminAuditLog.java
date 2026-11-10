package com.lottery.lottery_service.admin;

import static com.lottery.lottery_service.common.db.DbValues.utc;

import com.lottery.lottery_service.common.time.SeoulTime;
import java.time.Clock;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** 관리자 변경을 {@code admin_audit_event}에 남긴다. 보관 기간은 1년이다(D-020). */
@Component
public class AdminAuditLog {

  private final JdbcTemplate jdbc;
  private final TransactionTemplate independent;
  private final Clock clock;

  /** 기록기를 만든다. */
  public AdminAuditLog(
      JdbcTemplate jdbc, PlatformTransactionManager transactionManager, Clock clock) {
    this.jdbc = jdbc;
    this.independent = new TransactionTemplate(transactionManager);
    this.independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    this.clock = clock;
  }

  /** 변경과 같은 트랜잭션에 남긴다. 변경이 rollback되면 기록도 남지 않는다. 성공한 변경에 쓴다. */
  public void record(AdminAuditEvent event) {
    insert(event);
  }

  /** 호출한 트랜잭션과 따로 커밋한다. 거부되거나 실패해 rollback될 변경을 남길 때 쓴다. */
  public void recordIndependently(AdminAuditEvent event) {
    independent.executeWithoutResult(status -> insert(event));
  }

  private void insert(AdminAuditEvent event) {
    Instant now = clock.instant();
    Instant expiresAt = SeoulTime.atSeoul(now).plusYears(1).toInstant();
    jdbc.update(
        "INSERT INTO admin_audit_event (actor_member_id, action, target_type, target_key, reason,"
            + " before_json, after_json, outcome, occurred_at, expires_at)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        event.actorMemberId(),
        event.action(),
        event.targetType(),
        event.targetKey(),
        event.reason(),
        event.beforeJson(),
        event.afterJson(),
        event.outcome().name(),
        utc(now),
        utc(expiresAt));
  }
}
