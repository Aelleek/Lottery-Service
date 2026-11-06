package com.lottery.lottery_service.result.evaluate;

import com.lottery.lottery_service.common.number.LottoNumber;
import com.lottery.lottery_service.common.number.LottoNumbers;
import com.lottery.lottery_service.result.domain.PrizeRank;
import com.lottery.lottery_service.result.domain.WinningNumbers;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 기록을 게시본으로 판정한다(05 7절). 판정은 (기록, 게시본, 기록 버전)당 하나라 같은 묶음을 다시 돌려도 결과가 같다. 화면은 현재 게시본과 현재 기록 버전의 판정만
 * 등수로 보여 주고, 없으면 "확인 중"이다. 호출한 쪽 트랜잭션 안에서 부른다.
 */
@Component
public class EvaluationStore {

  private final JdbcTemplate jdbc;

  /** 저장소를 만든다. */
  public EvaluationStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * 게시본 하나로 그 회차의 기록을 ID 순으로 묶음 판정한다. 게시본이 현재 게시본이 아니면 판정하지 않고 멈춘다.
   *
   * @param resultId 게시본 ID
   * @param afterRecordId 이 ID 뒤의 기록부터. 처음이면 0
   * @param limit 묶음 크기
   */
  public EvaluationBatch evaluateBatch(long resultId, long afterRecordId, int limit) {
    Optional<Published> published = current(resultId);
    if (published.isEmpty()) {
      return new EvaluationBatch(afterRecordId, 0, true, true);
    }
    Published p = published.get();
    List<Map<String, Object>> records =
        jdbc.queryForList(
            "SELECT id, numbers_key, version FROM lotto_record WHERE round_no = ? AND id > ?"
                + " AND deleted_at IS NULL ORDER BY id LIMIT ?",
            p.roundNo(),
            afterRecordId,
            limit);
    long last = afterRecordId;
    for (Map<String, Object> r : records) {
      last = ((Number) r.get("id")).longValue();
      save(p, last, (String) r.get("numbers_key"), ((Number) r.get("version")).longValue());
    }
    return new EvaluationBatch(last, records.size(), records.size() < limit, false);
  }

  /**
   * 기록 하나를 현재 게시본으로 판정한다. 늦게 들어온 기록이나 바뀐 기록에 쓴다(05 7절 4번).
   *
   * @return 판정했으면 등수, 회차에 게시본이 없거나 기록이 지워졌으면 빈 값
   */
  public Optional<PrizeRank> evaluateRecord(long recordId) {
    List<Map<String, Object>> rows =
        jdbc.queryForList(
            "SELECT l.numbers_key, l.version, r.published_result_id FROM lotto_record l"
                + " JOIN lotto_round r ON r.round_no = l.round_no"
                + " WHERE l.id = ? AND l.deleted_at IS NULL",
            recordId);
    if (rows.isEmpty() || rows.get(0).get("published_result_id") == null) {
      return Optional.empty();
    }
    Map<String, Object> row = rows.get(0);
    Published p = current(((Number) row.get("published_result_id")).longValue()).orElseThrow();
    return Optional.of(
        save(
            p,
            recordId,
            (String) row.get("numbers_key"),
            ((Number) row.get("version")).longValue()));
  }

  /**
   * 화면에 보여 줄 등수. 현재 게시본과 현재 기록 버전의 판정만 쓴다.
   *
   * @return 등수. 아직 판정이 없으면("확인 중") 빈 값
   */
  public Optional<PrizeRank> latest(long recordId) {
    List<Map<String, Object>> rows =
        jdbc.queryForList(
            "SELECT e.matched_count, e.bonus_matched FROM lotto_record l"
                + " JOIN lotto_round r ON r.round_no = l.round_no"
                + " JOIN winning_evaluation e ON e.record_id = l.id"
                + " AND e.result_id = r.published_result_id AND e.record_version = l.version"
                + " WHERE l.id = ?",
            recordId);
    return rows.stream()
        .findFirst()
        .map(
            r ->
                PrizeRank.of(
                    ((Number) r.get("matched_count")).intValue(),
                    Boolean.TRUE.equals(r.get("bonus_matched"))));
  }

  private PrizeRank save(Published p, long recordId, String key, long version) {
    LottoNumbers ticket = LottoNumbers.fromKey(key);
    int matched = p.winning().main().matchCount(ticket);
    boolean bonus = ticket.contains(p.winning().bonus());
    PrizeRank rank = p.winning().rankOf(ticket);
    jdbc.update(
        "INSERT INTO winning_evaluation (record_id, result_id, round_no, record_version, rank_no,"
            + " matched_count, bonus_matched, evaluated_at) VALUES (?, ?, ?, ?, ?, ?, ?, NOW(6))"
            + " ON DUPLICATE KEY UPDATE record_id = record_id",
        recordId,
        p.resultId(),
        p.roundNo(),
        version,
        rank.isWinning() ? rank.ordinal() + 1 : null,
        matched,
        bonus);
    return rank;
  }

  private Optional<Published> current(long resultId) {
    List<Map<String, Object>> rows =
        jdbc.queryForList(
            "SELECT d.round_no, d.numbers_key, d.bonus_number FROM draw_result d"
                + " JOIN lotto_round r ON r.published_result_id = d.id WHERE d.id = ?",
            resultId);
    if (rows.isEmpty()) {
      return Optional.empty();
    }
    Map<String, Object> r = rows.get(0);
    return Optional.of(
        new Published(
            resultId,
            ((Number) r.get("round_no")).intValue(),
            new WinningNumbers(
                LottoNumbers.fromKey((String) r.get("numbers_key")),
                LottoNumber.of(((Number) r.get("bonus_number")).intValue()))));
  }

  private record Published(long resultId, int roundNo, WinningNumbers winning) {}
}
