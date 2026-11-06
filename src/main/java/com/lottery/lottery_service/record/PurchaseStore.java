package com.lottery.lottery_service.record;

import static com.lottery.lottery_service.common.db.DbValues.bytes;
import static com.lottery.lottery_service.common.db.DbValues.uuid;

import com.lottery.lottery_service.common.error.BusinessException;
import com.lottery.lottery_service.common.error.ErrorCode;
import com.lottery.lottery_service.common.error.ErrorDetails;
import com.lottery.lottery_service.common.job.JobStore;
import com.lottery.lottery_service.common.number.LottoNumbers;
import com.lottery.lottery_service.round.LockedRound;
import com.lottery.lottery_service.round.RoundStore;
import com.lottery.lottery_service.stats.UsageEventStore;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 구매 등록과 취소(05 3절, 02 3절). 호출한 쪽 트랜잭션 안에서 부른다. 잠금 순서는 주체 → 회차 → 기록 ID 오름차순이다(04 4절). 시각 컬럼은 DB 시각으로
 * 찍는다(D-045).
 */
@Component
public class PurchaseStore {

  /** 한 요청의 최대 세트 수. */
  public static final int MAX_SETS = 100;

  /** 판정 job 종류. */
  public static final String EVALUATE_RECORD_JOB = "EVALUATE_RECORD";

  private final JdbcTemplate jdbc;
  private final RoundStore rounds;
  private final UsageEventStore usage;
  private final JobStore jobs;

  /** 저장소를 만든다. */
  public PurchaseStore(JdbcTemplate jdbc, RoundStore rounds, UsageEventStore usage, JobStore jobs) {
    this.jdbc = jdbc;
    this.rounds = rounds;
    this.usage = usage;
    this.jobs = jobs;
  }

  /**
   * 한 회차의 번호를 구매 등록한다. 요청 안의 같은 조합은 하나로 합친다. 결과가 이미 게시된 회차면 바뀐 기록마다 판정 job을 등록한다.
   *
   * @throws BusinessException 0개나 100개 초과면 {@code VALIDATION_FAILED}, 회원 주체가 아니면 {@code FORBIDDEN}
   */
  public PurchaseResult register(long subjectId, int roundNo, List<LottoNumbers> sets) {
    if (sets.isEmpty() || sets.size() > MAX_SETS) {
      throw new BusinessException(ErrorCode.VALIDATION_FAILED, "한 번에 1~100세트까지 등록할 수 있습니다.");
    }
    Set<LottoNumbers> distinct = new LinkedHashSet<>(sets);
    lockMemberSubject(subjectId);
    LockedRound round = rounds.lockShared(roundNo);
    Map<String, Map<String, Object>> existing = lockExisting(subjectId, roundNo, distinct);

    List<PurchaseItem> items = new ArrayList<>();
    List<Long> changed = new ArrayList<>();
    for (LottoNumbers numbers : distinct) {
      Map<String, Object> row = existing.get(numbers.key());
      if (row == null) {
        UUID publicId = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO lotto_record (public_id, owner_subject_id, round_no, numbers_key,"
                + " purchased, purchased_at, created_at) VALUES (?, ?, ?, ?, TRUE, NOW(6), NOW(6))",
            bytes(publicId),
            subjectId,
            roundNo,
            numbers.key());
        changed.add(idOf(publicId));
        items.add(new PurchaseItem(numbers, publicId, PurchaseOutcome.CREATED));
        continue;
      }
      long id = ((Number) row.get("id")).longValue();
      UUID publicId = uuid((byte[]) row.get("public_id"));
      if (row.get("deleted_at") != null) {
        jdbc.update(
            "UPDATE lotto_record SET deleted_at = NULL, purchased = TRUE, purchased_at = NOW(6),"
                + " created_at = NOW(6), version = version + 1 WHERE id = ?",
            id);
        changed.add(id);
        items.add(new PurchaseItem(numbers, publicId, PurchaseOutcome.CREATED));
      } else if (Boolean.TRUE.equals(row.get("purchased"))) {
        items.add(new PurchaseItem(numbers, publicId, PurchaseOutcome.ALREADY_REGISTERED));
      } else {
        jdbc.update(
            "UPDATE lotto_record SET purchased = TRUE, purchased_at = NOW(6), version = version + 1"
                + " WHERE id = ?",
            id);
        changed.add(id);
        items.add(new PurchaseItem(numbers, publicId, PurchaseOutcome.CONVERTED));
      }
    }
    if (!changed.isEmpty()) {
      usage.record(
          subjectId, roundNo, UsageEventStore.PURCHASE_REGISTERED, changed.size(), round.dbNow());
      if (published(roundNo)) {
        for (long id : changed) {
          jobs.enqueue(
              EVALUATE_RECORD_JOB,
              "record:" + id + ":v" + version(id),
              "{\"recordId\":" + id + "}",
              round.dbNow());
        }
      }
    }
    boolean afterClose = !round.dbNow().isBefore(round.schedule().salesCloseAt());
    return new PurchaseResult(roundNo, items, afterClose);
  }

  /**
   * 구매 등록을 취소한다. 추천 이력이 있으면 구매 표시만 풀고, 없으면 기록을 숨긴다({@code deleted_at}).
   *
   * @return 취소 뒤 기록 버전
   * @throws BusinessException 본인 기록이 아니면 {@code NOT_FOUND}, 화면이 본 버전과 다르거나 구매 등록 상태가 아니면 {@code
   *     VERSION_CONFLICT}(현재 버전 포함)
   */
  public long cancel(long subjectId, UUID recordPublicId, long expectedVersion) {
    lockMemberSubject(subjectId);
    List<Map<String, Object>> rows =
        jdbc.queryForList(
            "SELECT id, version, purchased, recommended_at, deleted_at FROM lotto_record"
                + " WHERE public_id = ? AND owner_subject_id = ? FOR UPDATE",
            bytes(recordPublicId),
            subjectId);
    if (rows.isEmpty() || rows.get(0).get("deleted_at") != null) {
      throw new BusinessException(ErrorCode.NOT_FOUND, "요청한 내용을 찾을 수 없습니다.");
    }
    Map<String, Object> row = rows.get(0);
    long id = ((Number) row.get("id")).longValue();
    long version = ((Number) row.get("version")).longValue();
    if (version != expectedVersion || !Boolean.TRUE.equals(row.get("purchased"))) {
      throw new BusinessException(
          ErrorCode.VERSION_CONFLICT, "기록을 다시 확인해 주세요.", ErrorDetails.currentVersion(version));
    }
    if (row.get("recommended_at") != null) {
      jdbc.update(
          "UPDATE lotto_record SET purchased = FALSE, purchased_at = NULL, version = version + 1"
              + " WHERE id = ?",
          id);
    } else {
      jdbc.update(
          "UPDATE lotto_record SET deleted_at = NOW(6), purchased = FALSE, purchased_at = NULL,"
              + " version = version + 1 WHERE id = ?",
          id);
    }
    return version + 1;
  }

  private void lockMemberSubject(long subjectId) {
    List<String> kinds =
        jdbc.queryForList(
            "SELECT kind FROM actor_subject WHERE id = ? AND state = 'ACTIVE' FOR UPDATE",
            String.class,
            subjectId);
    if (kinds.isEmpty() || !"MEMBER".equals(kinds.get(0))) {
      throw new BusinessException(ErrorCode.FORBIDDEN, "허용되지 않은 요청입니다.");
    }
  }

  private Map<String, Map<String, Object>> lockExisting(
      long subjectId, int roundNo, Set<LottoNumbers> sets) {
    List<Object> args = new ArrayList<>(List.of(subjectId, roundNo));
    sets.forEach(s -> args.add(s.key()));
    List<Map<String, Object>> rows =
        jdbc.queryForList(
            "SELECT id, public_id, numbers_key, purchased, deleted_at FROM lotto_record"
                + " WHERE owner_subject_id = ? AND round_no = ? AND numbers_key IN ("
                + String.join(",", Collections.nCopies(sets.size(), "?"))
                + ") ORDER BY id FOR UPDATE",
            args.toArray());
    Map<String, Map<String, Object>> byKey = new HashMap<>();
    rows.forEach(r -> byKey.put((String) r.get("numbers_key"), r));
    return byKey;
  }

  private boolean published(int roundNo) {
    Long id =
        jdbc.queryForObject(
            "SELECT published_result_id FROM lotto_round WHERE round_no = ?", Long.class, roundNo);
    return id != null;
  }

  private long idOf(UUID publicId) {
    return jdbc.queryForObject(
        "SELECT id FROM lotto_record WHERE public_id = ?", Long.class, bytes(publicId));
  }

  private long version(long id) {
    return jdbc.queryForObject("SELECT version FROM lotto_record WHERE id = ?", Long.class, id);
  }
}
