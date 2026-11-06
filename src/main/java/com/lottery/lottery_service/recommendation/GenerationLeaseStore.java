package com.lottery.lottery_service.recommendation;

import static com.lottery.lottery_service.common.db.DbValues.instant;
import static com.lottery.lottery_service.common.db.DbValues.utc;

import com.lottery.lottery_service.common.error.BusinessException;
import com.lottery.lottery_service.common.error.ErrorCode;
import com.lottery.lottery_service.common.error.ErrorDetails;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 주체당 동시에 하나의 추천 생성만 허용하고, 생성이 끝난 뒤 최소 1초 간격을 둔다(02 2.1절, 05 2절 3번). {@code actor_subject}의 {@code
 * generation_lease_until}, {@code generation_fence}, {@code next_generation_at}을 쓴다.
 */
@Component
public class GenerationLeaseStore {

  /** 생성 권한 길이. 계산 상한(250ms)과 저장 시간을 넉넉히 덮는다. */
  public static final Duration LEASE = Duration.ofSeconds(10);

  /** 생성이 끝난 뒤 다음 생성까지의 최소 간격. */
  public static final Duration MIN_INTERVAL = Duration.ofSeconds(1);

  private final JdbcTemplate jdbc;
  private final TransactionTemplate tx;
  private final Clock clock;

  /** 저장소를 만든다. */
  public GenerationLeaseStore(
      JdbcTemplate jdbc, PlatformTransactionManager transactionManager, Clock clock) {
    this.jdbc = jdbc;
    this.tx = new TransactionTemplate(transactionManager);
    this.clock = clock;
  }

  /**
   * 생성 권한을 잡는다. 자기 트랜잭션에서 주체 행을 잠그고 바로 커밋한다.
   *
   * @throws BusinessException 생성 중이거나 간격 전이면 {@code GENERATION_BUSY}(다시 시도할 시각 포함), 주체가 활성 상태가 아니면
   *     {@code FORBIDDEN}
   */
  public GenerationLease acquire(long subjectId) {
    return tx.execute(
        status -> {
          Instant now = clock.instant();
          Map<String, Object> row = lockSubject(subjectId);
          if (!"ACTIVE".equals(row.get("state"))) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "허용되지 않은 요청입니다.");
          }
          Instant leaseUntil = instant((LocalDateTime) row.get("generation_lease_until"));
          Instant nextAt = instant((LocalDateTime) row.get("next_generation_at"));
          Instant retryAt = later(leaseUntil, nextAt);
          if (retryAt != null && retryAt.isAfter(now)) {
            throw new BusinessException(
                ErrorCode.GENERATION_BUSY, "잠시 뒤 다시 시도해 주세요.", ErrorDetails.retryAt(retryAt));
          }
          long fence = ((Number) row.get("generation_fence")).longValue() + 1;
          Instant until = now.plus(LEASE);
          jdbc.update(
              "UPDATE actor_subject SET generation_fence = ?, generation_lease_until = ?"
                  + " WHERE id = ?",
              fence,
              utc(until),
              subjectId);
          return new GenerationLease(subjectId, fence, until);
        });
  }

  /**
   * 저장 트랜잭션 안에서 주체 행을 잠그고 이 생성이 아직 최신인지 확인한다. 호출한 쪽 트랜잭션 안에서 불러야 한다.
   *
   * @throws GenerationSupersededException 다른 생성이 이미 시작됐을 때
   */
  public void requireCurrent(GenerationLease lease) {
    Map<String, Object> row = lockSubject(lease.subjectId());
    if (((Number) row.get("generation_fence")).longValue() != lease.fence()) {
      throw new GenerationSupersededException(lease.subjectId(), lease.fence());
    }
  }

  /**
   * 생성을 끝낸다. 권한을 풀고 다음 생성 가능 시각을 지금부터 1초 뒤로 둔다. 다른 생성이 이미 시작됐으면 아무것도 바꾸지 않는다. 호출한 쪽 트랜잭션이 있으면 거기에
   * 참여한다.
   *
   * @return 이 생성이 최신이어서 권한을 풀었으면 {@code true}
   */
  public boolean finish(GenerationLease lease) {
    Instant now = clock.instant();
    int changed =
        jdbc.update(
            "UPDATE actor_subject SET generation_lease_until = NULL, next_generation_at = ?"
                + " WHERE id = ? AND generation_fence = ?",
            utc(now.plus(MIN_INTERVAL)),
            lease.subjectId(),
            lease.fence());
    return changed == 1;
  }

  private Map<String, Object> lockSubject(long subjectId) {
    List<Map<String, Object>> rows =
        jdbc.queryForList(
            "SELECT state, generation_fence, generation_lease_until, next_generation_at"
                + " FROM actor_subject WHERE id = ? FOR UPDATE",
            subjectId);
    if (rows.isEmpty()) {
      throw new BusinessException(ErrorCode.FORBIDDEN, "허용되지 않은 요청입니다.");
    }
    return rows.get(0);
  }

  private static Instant later(Instant a, Instant b) {
    if (a == null) {
      return b;
    }
    if (b == null) {
      return a;
    }
    return a.isAfter(b) ? a : b;
  }
}
