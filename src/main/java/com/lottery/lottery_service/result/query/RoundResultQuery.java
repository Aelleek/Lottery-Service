package com.lottery.lottery_service.result.query;

import com.lottery.lottery_service.common.db.DbValues;
import com.lottery.lottery_service.common.number.LottoNumber;
import com.lottery.lottery_service.common.number.LottoNumbers;
import com.lottery.lottery_service.result.domain.WinningNumbers;
import com.lottery.lottery_service.result.source.ResultCollectJobHandler;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 회차 결과를 읽는다(06 2절 {@code GET /lotto/rounds/{round}/result}). 게시본이 없으면 그 회차의 마지막 수집 시도 결과로 미발표와 수집
 * 지연을 나눈다.
 */
@Component
public class RoundResultQuery {

  private final JdbcTemplate jdbc;

  /** 조회기를 만든다. */
  public RoundResultQuery(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * 회차 결과.
   *
   * @return 등록된 회차가 아니면 빈 값
   */
  public Optional<RoundResult> find(int roundNo) {
    List<Map<String, Object>> rounds =
        jdbc.queryForList(
            "SELECT r.publication_version, r.published_result_id, d.numbers_key, d.bonus_number,"
                + " GREATEST(d.fetched_at, COALESCE(d.api_confirmed_at, d.fetched_at)) AS checked_at,"
                + " d.source, d.api_confirmed_at"
                + " FROM lotto_round r LEFT JOIN draw_result d ON d.id = r.published_result_id"
                + " WHERE r.round_no = ?",
            roundNo);
    if (rounds.isEmpty()) {
      return Optional.empty();
    }
    Map<String, Object> row = rounds.get(0);
    if (row.get("published_result_id") == null) {
      return Optional.of(unpublished(roundNo));
    }
    long resultId = ((Number) row.get("published_result_id")).longValue();
    WinningNumbers winning =
        new WinningNumbers(
            LottoNumbers.fromKey((String) row.get("numbers_key")),
            LottoNumber.of(((Number) row.get("bonus_number")).intValue()));
    List<Prize> prizes =
        jdbc.query(
            "SELECT rank_no, prize_per_game, winner_count FROM draw_prize WHERE result_id = ?"
                + " ORDER BY rank_no",
            (rs, i) ->
                new Prize(
                    rs.getInt("rank_no"),
                    (Long) rs.getObject("prize_per_game", Long.class),
                    (Long) rs.getObject("winner_count", Long.class)),
            resultId);
    return Optional.of(
        new RoundResult(
            roundNo,
            ResultState.PUBLISHED,
            ((Number) row.get("publication_version")).longValue(),
            winning,
            prizes,
            instant(row.get("checked_at")),
            (String) row.get("source"),
            instant(row.get("api_confirmed_at"))));
  }

  private RoundResult unpublished(int roundNo) {
    byte[] prefix =
        (ResultCollectJobHandler.dedupePrefix(roundNo) + "%").getBytes(StandardCharsets.UTF_8);
    List<Map<String, Object>> attempts =
        jdbc.queryForList(
            "SELECT last_error_code, finished_at FROM job WHERE job_type = ? AND dedupe_key LIKE ?"
                + " AND (finished_at IS NOT NULL OR last_error_code IS NOT NULL)"
                + " ORDER BY id DESC LIMIT 1",
            ResultCollectJobHandler.JOB,
            prefix);
    if (attempts.isEmpty()) {
      return new RoundResult(roundNo, ResultState.NOT_PUBLISHED, null, null, List.of(), null);
    }
    Object code = attempts.get(0).get("last_error_code");
    ResultState state =
        code == null || ResultCollectJobHandler.NOT_ANNOUNCED.equals(code)
            ? ResultState.NOT_PUBLISHED
            : ResultState.DELAYED;
    return new RoundResult(
        roundNo, state, null, null, List.of(), instant(attempts.get(0).get("finished_at")));
  }

  private static Instant instant(Object value) {
    return value == null ? null : DbValues.instant((LocalDateTime) value);
  }
}
