package com.lottery.lottery_service.recommendation;

import static com.lottery.lottery_service.common.db.DbValues.bytes;
import static com.lottery.lottery_service.common.db.DbValues.utc;

import com.lottery.lottery_service.common.number.LottoNumbers;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Component;

/**
 * 추천 이력을 읽고 성공한 추천을 저장한다(05 2절 4·8·9·10번). 저장은 호출한 쪽 트랜잭션 안에서 주체와 회차를 잠근 뒤 부른다.
 *
 * <p>이력은 그 주체와, 그 주체가 귀속한 비회원 주체의 같은 회차 추천 조합을 합친 것이다.
 */
@Component
public class RecommendationStore {

  private final JdbcTemplate jdbc;

  /** 저장소를 만든다. */
  public RecommendationStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** 주체가 이 회차에 이미 받은 조합. 귀속한 비회원 주체의 이력도 합친다. */
  public Set<LottoNumbers> seen(long subjectId, int roundNo) {
    List<String> keys =
        jdbc.queryForList(
            "SELECT numbers_key FROM recommendation_seen WHERE round_no = ? AND (subject_id = ?"
                + " OR subject_id IN (SELECT guest_subject_id FROM subject_claim"
                + " WHERE member_subject_id = ?))",
            String.class,
            roundNo,
            subjectId,
            subjectId);
    Set<LottoNumbers> seen = new HashSet<>();
    keys.forEach(k -> seen.add(LottoNumbers.fromKey(k)));
    return seen;
  }

  /**
   * 추천을 저장한다. 이력을 다시 읽어 그 사이 같은 조합이 들어갔으면 거부한다. 요청, 5개 항목, 이력, 기록을 한 트랜잭션에 넣는다. 이미 있는 기록에는 추천 표시만
   * 더하고 구매 상태는 바꾸지 않는다.
   *
   * @throws RecommendationConflictException 그 사이 같은 조합이 이력에 들어갔을 때
   */
  public SavedRecommendation save(NewRecommendation r) {
    Set<LottoNumbers> current = seen(r.subjectId(), r.roundNo());
    if (r.sets().stream().anyMatch(current::contains)) {
      throw new RecommendationConflictException(r.subjectId(), r.roundNo());
    }
    UUID publicId = UUID.randomUUID();
    long requestId = insertRequest(r, publicId);
    List<Long> recordIds = new ArrayList<>();
    for (int i = 0; i < r.sets().size(); i++) {
      String key = r.sets().get(i).key();
      long recordId = upsertRecord(r.subjectId(), r.roundNo(), key);
      recordIds.add(recordId);
      jdbc.update(
          "INSERT INTO recommendation_item (request_id, ordinal, record_id, numbers_key)"
              + " VALUES (?, ?, ?, ?)",
          requestId,
          i + 1,
          recordId,
          key);
      try {
        jdbc.update(
            "INSERT INTO recommendation_seen (subject_id, round_no, numbers_key,"
                + " first_request_id) VALUES (?, ?, ?, ?)",
            r.subjectId(),
            r.roundNo(),
            key,
            requestId);
      } catch (DuplicateKeyException e) {
        throw new RecommendationConflictException(r.subjectId(), r.roundNo());
      }
    }
    return new SavedRecommendation(requestId, publicId, recordIds);
  }

  private long insertRequest(NewRecommendation r, UUID publicId) {
    KeyHolder keys = new GeneratedKeyHolder();
    jdbc.update(
        con -> {
          PreparedStatement ps =
              con.prepareStatement(
                  "INSERT INTO recommendation_request (public_id, subject_id, round_no,"
                      + " key_digest, request_hash, accepted_at, policy_version,"
                      + " generation_mode, filter_version, condition_json, response_expires_at)"
                      + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                  Statement.RETURN_GENERATED_KEYS);
          ps.setBytes(1, bytes(publicId));
          ps.setLong(2, r.subjectId());
          ps.setInt(3, r.roundNo());
          ps.setBytes(4, r.keyDigest());
          ps.setBytes(5, r.requestHash());
          ps.setObject(6, utc(r.acceptedAt()));
          ps.setLong(7, r.policyVersion());
          ps.setString(8, r.filterVersion() != null ? "BASIC" : "CONDITION");
          ps.setString(9, r.filterVersion());
          ps.setString(10, r.conditionJson());
          ps.setObject(11, utc(r.responseExpiresAt()));
          return ps;
        },
        keys);
    return keys.getKey().longValue();
  }

  private long upsertRecord(long subjectId, int roundNo, String key) {
    List<Map<String, Object>> existing =
        jdbc.queryForList(
            "SELECT id, deleted_at FROM lotto_record WHERE owner_subject_id = ? AND round_no = ?"
                + " AND numbers_key = ? FOR UPDATE",
            subjectId,
            roundNo,
            key);
    if (existing.isEmpty()) {
      KeyHolder keys = new GeneratedKeyHolder();
      jdbc.update(
          con -> {
            PreparedStatement ps =
                con.prepareStatement(
                    "INSERT INTO lotto_record (public_id, owner_subject_id, round_no, numbers_key,"
                        + " recommended_at, created_at) VALUES (?, ?, ?, ?, NOW(6), NOW(6))",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setBytes(1, bytes(UUID.randomUUID()));
            ps.setLong(2, subjectId);
            ps.setInt(3, roundNo);
            ps.setString(4, key);
            return ps;
          },
          keys);
      return keys.getKey().longValue();
    }
    long id = ((Number) existing.get(0).get("id")).longValue();
    if (existing.get(0).get("deleted_at") != null) {
      jdbc.update(
          "UPDATE lotto_record SET deleted_at = NULL, purchased = FALSE, purchased_at = NULL,"
              + " recommended_at = NOW(6), created_at = NOW(6), version = version + 1"
              + " WHERE id = ?",
          id);
    } else {
      jdbc.update(
          "UPDATE lotto_record SET recommended_at = COALESCE(recommended_at, NOW(6)),"
              + " version = version + 1 WHERE id = ?",
          id);
    }
    return id;
  }
}
