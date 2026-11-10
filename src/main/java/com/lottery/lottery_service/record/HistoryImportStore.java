package com.lottery.lottery_service.record;

import static com.lottery.lottery_service.common.db.DbValues.bytes;

import com.lottery.lottery_service.common.error.BusinessException;
import com.lottery.lottery_service.common.error.ErrorCode;
import com.lottery.lottery_service.common.job.JobStore;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 귀속한 비회원 기록을 회원 기록으로 가져온다(05 4절, 02 7.1절). 잠금 순서는 두 주체(ID 오름차순) → 회차(공유) → 기록이고, 마감 판단은 회차 잠금 뒤 읽은
 * DB 시각으로 한다. 원본 기록 ID 순으로 묶음 단위로 처리하고, 원본마다 결과를 {@code history_import_item}에 남겨 다시 실행해도 같은 원본을 두 번
 * 처리하지 않는다. 호출한 쪽 트랜잭션 안에서 묶음 하나를 처리한다.
 *
 * <ul>
 *   <li>회원 쪽에 같은 조합이 없으면 새 기록(CREATED). 원래 추천 시각을 유지하고 {@code created_at}은 지금이다. 구매 표시는 옮기지 않는다.
 *   <li>있으면 합친다(MERGED). 판매 마감 전 회차면 추천 표시가 없을 때 원래 추천 시각을 붙이고, 마감 뒤 회차면 붙이지 않는다.
 *   <li>회원이 지운 기록과 같은 조합이면 되살리지 않는다(SKIPPED_TARGET_DELETED).
 *   <li>보관 기한이 지난 원본은 가져오지 않는다(SKIPPED_SOURCE_EXPIRED). 기한은 기록의 {@code expires_at}, 없으면 추첨일+30일(02
 *       8절)이다. 정리기가 아직 지우지 않았어도 기한이 지났으면 가져오지 않는다.
 * </ul>
 */
@Component
public class HistoryImportStore {

  private final JdbcTemplate jdbc;
  private final JobStore jobs;

  /** 저장소를 만든다. */
  public HistoryImportStore(JdbcTemplate jdbc, JobStore jobs) {
    this.jdbc = jdbc;
    this.jobs = jobs;
  }

  /**
   * 묶음 하나를 가져온다.
   *
   * @param memberSubjectId 가져오기를 요청한 회원 주체 ID. 귀속의 회원 주체와 같아야 한다
   * @param claimId 귀속 ID
   * @param afterSourceId 이 ID 뒤의 원본부터 처리한다. 처음이면 0
   * @param limit 묶음 크기
   * @throws BusinessException 귀속이 없거나, 다른 회원의 귀속이거나, 회원 주체가 활성 상태가 아니면 {@code NOT_FOUND}
   */
  public ImportBatch importBatch(
      long memberSubjectId, long claimId, long afterSourceId, int limit) {
    Map<String, Object> claim = lockClaim(claimId);
    if (((Number) claim.get("member_subject_id")).longValue() != memberSubjectId) {
      throw notFound();
    }
    long guest = ((Number) claim.get("guest_subject_id")).longValue();
    long member = ((Number) claim.get("member_subject_id")).longValue();
    List<Map<String, Object>> sources =
        jdbc.queryForList(
            "SELECT s.id, s.round_no, s.numbers_key, s.recommended_at,"
                + " COALESCE(s.expires_at, r.draw_at + INTERVAL 30 DAY) AS expires_at,"
                + " COALESCE(r.cutoff_close_at, r.sales_close_at) AS close_at,"
                + " r.published_result_id FROM lotto_record s"
                + " JOIN lotto_round r ON r.round_no = s.round_no"
                + " WHERE s.owner_subject_id = ? AND s.id > ? AND s.deleted_at IS NULL"
                + " AND NOT EXISTS (SELECT 1 FROM history_import_item h WHERE h.claim_id = ?"
                + " AND h.source_record_id = s.id) ORDER BY s.id LIMIT ?",
            guest,
            afterSourceId,
            claimId,
            limit);
    TreeSet<Integer> rounds = new TreeSet<>();
    sources.forEach(r -> rounds.add(((Number) r.get("round_no")).intValue()));
    if (!rounds.isEmpty()) {
      jdbc.queryForList(
          "SELECT round_no FROM lotto_round WHERE round_no IN ("
              + String.join(",", Collections.nCopies(rounds.size(), "?"))
              + ") ORDER BY round_no FOR SHARE",
          Integer.class,
          rounds.toArray());
    }
    Instant now = dbNow();
    TreeSet<Integer> afterClose = new TreeSet<>();
    long last = afterSourceId;
    for (Map<String, Object> source : sources) {
      last = ((Number) source.get("id")).longValue();
      importOne(claimId, member, source, now, afterClose);
    }
    boolean finished = sources.size() < limit;
    if (finished) {
      jdbc.update(
          "UPDATE subject_claim SET imported_at = NOW(6), version = version + 1"
              + " WHERE id = ? AND imported_at IS NULL",
          claimId);
    }
    return new ImportBatch(last, sources.size(), finished, afterClose);
  }

  private void importOne(
      long claimId,
      long member,
      Map<String, Object> source,
      Instant now,
      TreeSet<Integer> afterClose) {
    long sourceId = ((Number) source.get("id")).longValue();
    int round = ((Number) source.get("round_no")).intValue();
    String key = (String) source.get("numbers_key");
    LocalDateTime expiresAt = (LocalDateTime) source.get("expires_at");
    if (expiresAt != null && !expiresAt.isAfter(LocalDateTime.ofInstant(now, ZoneOffset.UTC))) {
      item(claimId, sourceId, null, "SKIPPED_SOURCE_EXPIRED");
      return;
    }
    boolean closed =
        !((LocalDateTime) source.get("close_at"))
            .isAfter(LocalDateTime.ofInstant(now, ZoneOffset.UTC));
    List<Map<String, Object>> targets =
        jdbc.queryForList(
            "SELECT id, deleted_at, recommended_at FROM lotto_record WHERE owner_subject_id = ?"
                + " AND round_no = ? AND numbers_key = ? FOR UPDATE",
            member,
            round,
            key);
    long targetId;
    if (targets.isEmpty()) {
      UUID publicId = UUID.randomUUID();
      jdbc.update(
          "INSERT INTO lotto_record (public_id, owner_subject_id, round_no, numbers_key,"
              + " recommended_at, created_at) VALUES (?, ?, ?, ?, ?, NOW(6))",
          bytes(publicId),
          member,
          round,
          key,
          source.get("recommended_at"));
      targetId =
          jdbc.queryForObject(
              "SELECT id FROM lotto_record WHERE public_id = ?", Long.class, bytes(publicId));
      item(claimId, sourceId, targetId, "CREATED");
    } else if (targets.get(0).get("deleted_at") != null) {
      item(claimId, sourceId, null, "SKIPPED_TARGET_DELETED");
      return;
    } else {
      targetId = ((Number) targets.get(0).get("id")).longValue();
      if (!closed
          && targets.get(0).get("recommended_at") == null
          && source.get("recommended_at") != null) {
        jdbc.update(
            "UPDATE lotto_record SET recommended_at = ?, version = version + 1 WHERE id = ?",
            source.get("recommended_at"),
            targetId);
      }
      item(claimId, sourceId, targetId, "MERGED");
    }
    if (closed) {
      afterClose.add(round);
    }
    if (source.get("published_result_id") != null) {
      long version =
          jdbc.queryForObject(
              "SELECT version FROM lotto_record WHERE id = ?", Long.class, targetId);
      jobs.enqueue(
          PurchaseStore.EVALUATE_RECORD_JOB,
          "record:" + targetId + ":v" + version,
          "{\"recordId\":" + targetId + "}",
          now);
    }
  }

  private void item(long claimId, long sourceId, Long targetId, String outcome) {
    jdbc.update(
        "INSERT INTO history_import_item (claim_id, source_record_id, target_record_id, outcome)"
            + " VALUES (?, ?, ?, ?)",
        claimId,
        sourceId,
        targetId,
        outcome);
  }

  private Map<String, Object> lockClaim(long claimId) {
    List<Map<String, Object>> claims =
        jdbc.queryForList(
            "SELECT guest_subject_id, member_subject_id FROM subject_claim WHERE id = ?", claimId);
    if (claims.isEmpty()) {
      throw notFound();
    }
    long guest = ((Number) claims.get(0).get("guest_subject_id")).longValue();
    long member = ((Number) claims.get(0).get("member_subject_id")).longValue();
    List<Map<String, Object>> subjects =
        jdbc.queryForList(
            "SELECT id, state FROM actor_subject WHERE id IN (?, ?) ORDER BY id FOR UPDATE",
            Math.min(guest, member),
            Math.max(guest, member));
    boolean memberActive =
        subjects.stream()
            .anyMatch(
                s ->
                    ((Number) s.get("id")).longValue() == member
                        && "ACTIVE".equals(s.get("state")));
    if (!memberActive) {
      throw notFound();
    }
    return claims.get(0);
  }

  private Instant dbNow() {
    return jdbc.queryForObject("SELECT NOW(6)", LocalDateTime.class).toInstant(ZoneOffset.UTC);
  }

  private static BusinessException notFound() {
    return new BusinessException(ErrorCode.NOT_FOUND, "요청한 내용을 찾을 수 없습니다.");
  }
}
