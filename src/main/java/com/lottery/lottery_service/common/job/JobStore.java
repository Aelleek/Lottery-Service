package com.lottery.lottery_service.common.job;

import static com.lottery.lottery_service.common.db.DbValues.bytes;
import static com.lottery.lottery_service.common.db.DbValues.instant;
import static com.lottery.lottery_service.common.db.DbValues.utc;
import static com.lottery.lottery_service.common.db.DbValues.uuid;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@code job} 테이블을 다룬다. 등록과 lease 검사 저장은 호출한 쪽 트랜잭션에 참여하고, 가져가기와 회수는 자기 트랜잭션에서 한다.
 *
 * <p>시각은 주입한 {@link Clock}으로 정하고 UTC {@code DATETIME(6)}로 저장한다.
 */
@Component
public class JobStore {

  /** DEAD가 된 job마다 등록하는 운영자 알림 job 종류. */
  public static final String OPERATOR_ALERT = "OPERATOR_ALERT";

  private static final String COLUMNS =
      "id, job_type, payload, cursor_json, lease_token, lease_until, attempt_count";

  private final JdbcTemplate jdbc;
  private final TransactionTemplate tx;
  private final Clock clock;
  private final JobProperties properties;

  /** 저장소를 만든다. */
  public JobStore(
      JdbcTemplate jdbc,
      PlatformTransactionManager transactionManager,
      Clock clock,
      JobProperties properties) {
    this.jdbc = jdbc;
    this.tx = new TransactionTemplate(transactionManager);
    this.clock = clock;
    this.properties = properties;
  }

  /**
   * job을 등록한다. 같은 (종류, 중복 키)가 이미 있으면 아무것도 바꾸지 않는다.
   *
   * @return 새로 등록했으면 {@code true}
   */
  public boolean enqueue(String type, String dedupeKey, String payloadJson, Instant availableAt) {
    try {
      jdbc.update(
          "INSERT INTO job (public_id, job_type, dedupe_key, state, payload, available_at)"
              + " VALUES (?, ?, ?, 'READY', ?, ?)",
          bytes(UUID.randomUUID()),
          type,
          dedupeKey.getBytes(StandardCharsets.UTF_8),
          payloadJson,
          utc(availableAt));
      return true;
    } catch (DuplicateKeyException e) {
      return false;
    }
  }

  /**
   * 처리할 때가 된 job 하나를 가져가 RUNNING으로 바꾸고 새 lease를 준다. 다른 worker가 잠근 행은 건너뛴다.
   *
   * @return 가져간 job. 처리할 job이 없으면 빈 값
   */
  public Optional<JobLease> claim(List<String> types) {
    if (types.isEmpty()) {
      return Optional.empty();
    }
    return tx.execute(status -> claimInTransaction(types));
  }

  private Optional<JobLease> claimInTransaction(List<String> types) {
    Instant now = clock.instant();
    String placeholders = String.join(",", types.stream().map(t -> "?").toList());
    List<Object> params = new ArrayList<>(types);
    params.add(utc(now));
    List<Long> ids =
        jdbc.queryForList(
            "SELECT id FROM job WHERE state IN ('READY','RETRY_WAIT') AND job_type IN ("
                + placeholders
                + ") AND available_at <= ? ORDER BY available_at, id LIMIT 1"
                + " FOR UPDATE SKIP LOCKED",
            Long.class,
            params.toArray());
    if (ids.isEmpty()) {
      return Optional.empty();
    }
    long id = ids.get(0);
    UUID token = UUID.randomUUID();
    Instant leaseUntil = now.plus(properties.leaseDuration());
    jdbc.update(
        "UPDATE job SET state = 'RUNNING', lease_token = ?, lease_until = ? WHERE id = ?",
        bytes(token),
        utc(leaseUntil),
        id);
    return Optional.of(
        jdbc.queryForObject("SELECT " + COLUMNS + " FROM job WHERE id = ?", this::lease, id));
  }

  /**
   * 진행 위치를 저장한다. lease가 아직 자기 것이고 만료 전일 때만 저장한다.
   *
   * @throws LeaseLostException lease를 잃었을 때. 호출한 트랜잭션을 rollback해야 한다
   */
  public void saveProgress(JobLease lease, String cursorJson) {
    int changed =
        jdbc.update(
            "UPDATE job SET cursor_json = ? WHERE id = ? AND state = 'RUNNING'"
                + " AND lease_token = ? AND lease_until > ?",
            cursorJson,
            lease.id(),
            bytes(lease.token()),
            utc(clock.instant()));
    requireHeld(lease, changed);
  }

  /**
   * job을 끝낸다(SUCCEEDED).
   *
   * @throws LeaseLostException lease를 잃었을 때
   */
  public void complete(JobLease lease) {
    Instant now = clock.instant();
    int changed =
        jdbc.update(
            "UPDATE job SET state = 'SUCCEEDED', finished_at = ?, lease_token = NULL,"
                + " lease_until = NULL WHERE id = ? AND state = 'RUNNING' AND lease_token = ?"
                + " AND lease_until > ?",
            utc(now),
            lease.id(),
            bytes(lease.token()),
            utc(now));
    requireHeld(lease, changed);
  }

  /**
   * 처리 실패를 남긴다. 시도 횟수를 늘리고, 상한에 닿으면 DEAD로 두고 운영자 알림 job을 등록한다. 아니면 {@code retryAt}까지 RETRY_WAIT로
   * 둔다.
   *
   * @return 실패를 남긴 뒤 상태
   * @throws LeaseLostException lease를 잃었을 때
   */
  public JobState fail(JobLease lease, String errorCode, Instant retryAt) {
    Instant now = clock.instant();
    int attempts = lease.attemptCount() + 1;
    JobState next = attempts >= properties.maxAttempts() ? JobState.DEAD : JobState.RETRY_WAIT;
    int changed =
        jdbc.update(
            "UPDATE job SET state = ?, attempt_count = ?, last_error_code = ?, available_at = ?,"
                + " finished_at = ?, lease_token = NULL, lease_until = NULL"
                + " WHERE id = ? AND state = 'RUNNING' AND lease_token = ? AND lease_until > ?",
            next.name(),
            attempts,
            errorCode,
            utc(next == JobState.DEAD ? now : retryAt),
            next == JobState.DEAD ? utc(now) : null,
            lease.id(),
            bytes(lease.token()),
            utc(now));
    requireHeld(lease, changed);
    if (next == JobState.DEAD) {
      enqueueDeadAlert(lease.id(), lease.type(), now);
    }
    return next;
  }

  /**
   * lease가 만료된 RUNNING job을 회수한다. 시도 횟수를 늘려 RETRY_WAIT로 돌리고, 상한에 닿으면 DEAD로 두고 운영자 알림 job을 등록한다. 회수와
   * 알림 등록은 한 트랜잭션이다.
   *
   * @return 회수한 job
   */
  public List<RecoveredJob> recoverExpired() {
    return tx.execute(status -> recoverInTransaction());
  }

  private List<RecoveredJob> recoverInTransaction() {
    Instant now = clock.instant();
    List<Object[]> expired =
        jdbc.query(
            "SELECT id, job_type, attempt_count FROM job WHERE state = 'RUNNING'"
                + " AND lease_until <= ? ORDER BY id FOR UPDATE",
            (rs, n) -> new Object[] {rs.getLong(1), rs.getString(2), rs.getInt(3)},
            utc(now));
    List<RecoveredJob> recovered = new ArrayList<>();
    for (Object[] row : expired) {
      long id = (Long) row[0];
      String type = (String) row[1];
      int attempts = (Integer) row[2] + 1;
      JobState next = attempts >= properties.maxAttempts() ? JobState.DEAD : JobState.RETRY_WAIT;
      jdbc.update(
          "UPDATE job SET state = ?, attempt_count = ?, last_error_code = 'LEASE_EXPIRED',"
              + " available_at = ?, finished_at = ?, lease_token = NULL, lease_until = NULL"
              + " WHERE id = ?",
          next.name(),
          attempts,
          utc(now),
          next == JobState.DEAD ? utc(now) : null,
          id);
      if (next == JobState.DEAD) {
        enqueueDeadAlert(id, type, now);
      }
      recovered.add(new RecoveredJob(id, type, next, attempts));
    }
    return recovered;
  }

  private void enqueueDeadAlert(long jobId, String type, Instant now) {
    if (OPERATOR_ALERT.equals(type)) {
      return;
    }
    enqueue(
        OPERATOR_ALERT,
        "job-dead:" + jobId,
        "{\"kind\":\"JOB_DEAD\",\"jobId\":" + jobId + "}",
        now);
  }

  private static void requireHeld(JobLease lease, int changed) {
    if (changed != 1) {
      throw new LeaseLostException(lease.id());
    }
  }

  private JobLease lease(ResultSet rs, int rowNum) throws SQLException {
    return new JobLease(
        rs.getLong("id"),
        rs.getString("job_type"),
        rs.getString("payload"),
        rs.getString("cursor_json"),
        uuid(rs.getBytes("lease_token")),
        instant(rs.getObject("lease_until", LocalDateTime.class)),
        rs.getInt("attempt_count"));
  }
}
