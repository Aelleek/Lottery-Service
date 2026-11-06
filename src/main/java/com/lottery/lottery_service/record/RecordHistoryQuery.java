package com.lottery.lottery_service.record;

import static com.lottery.lottery_service.common.db.DbValues.instant;
import static com.lottery.lottery_service.common.db.DbValues.uuid;

import com.lottery.lottery_service.common.error.BusinessException;
import com.lottery.lottery_service.common.error.ErrorCode;
import com.lottery.lottery_service.common.number.LottoNumbers;
import com.lottery.lottery_service.common.secret.SecretKeys;
import com.lottery.lottery_service.result.query.RoundResultQuery;
import com.lottery.lottery_service.round.RoundStore;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 본인 기록을 회차별로 10개씩 보여 준다(02 4절, 06 2.1절). 회차를 고르지 않으면 이번 회차(판매 중, 마감 구간에는 방금 마감한 회차)다.
 *
 * <p>cursor는 (회차, 마지막 ID, 첫 쪽 시점의 최대 ID, 필터)를 서명한 값이라 변조하거나 다른 필터에 쓸 수 없다. 첫 쪽 뒤에 생긴 기록은 이어지는 쪽에
 * 끼어들지 않는다.
 */
@Component
public class RecordHistoryQuery {

  /** 한 쪽 크기. */
  public static final int PAGE_SIZE = 10;

  private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
  private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

  private final JdbcTemplate jdbc;
  private final RoundStore rounds;
  private final SecretKeys secrets;
  private final Clock clock;
  private final RoundResultQuery results;

  /** 조회기를 만든다. */
  public RecordHistoryQuery(
      JdbcTemplate jdbc,
      RoundStore rounds,
      SecretKeys secrets,
      Clock clock,
      RoundResultQuery results) {
    this.jdbc = jdbc;
    this.rounds = rounds;
    this.secrets = secrets;
    this.clock = clock;
    this.results = results;
  }

  /**
   * 기록 한 쪽을 읽는다.
   *
   * @param subjectId 주체 ID
   * @param round 고른 회차. 고르지 않았으면 {@code null}
   * @param purchased 구매 여부 필터. 없으면 {@code null}
   * @param cursor 앞 쪽의 {@code nextCursor}. 첫 쪽이면 {@code null}
   * @throws BusinessException cursor가 틀렸거나 다른 조건의 것이면 {@code VALIDATION_FAILED}, 회차를 고르지 않았는데 일정이
   *     없으면 {@code SCHEDULE_UNAVAILABLE}
   */
  public RecordPage list(long subjectId, Integer round, Boolean purchased, String cursor) {
    return list(subjectId, round, purchased, null, cursor);
  }

  /**
   * 등수 필터를 더해 기록 한 쪽을 읽는다. 등수는 현재 게시본과 현재 기록 버전의 판정이다. 판정이 없는 기록은 낙첨에 넣지 않는다(06 2.1절).
   *
   * @param rank {@code "1"}~{@code "5"} 또는 {@code "LOSING"}. 없으면 {@code null}
   * @throws BusinessException 등수 값이 틀렸으면 {@code VALIDATION_FAILED}. 나머지는 4인자 메서드와 같다
   */
  public RecordPage list(
      long subjectId, Integer round, Boolean purchased, String rank, String cursor) {
    String rankClause = rankClause(rank);
    int selected =
        round != null ? round : rounds.calendar().contextAt(clock.instant()).currentRound();
    String filter = selected + "|" + purchased + (rank == null ? "" : "|" + rank);
    long afterId = Long.MAX_VALUE;
    long snapshot;
    if (cursor != null) {
      long[] decoded = decode(cursor, subjectId, filter);
      afterId = decoded[0];
      snapshot = decoded[1];
    } else {
      Long max =
          jdbc.queryForObject(
              "SELECT MAX(id) FROM lotto_record WHERE owner_subject_id = ? AND round_no = ?",
              Long.class,
              subjectId,
              selected);
      snapshot = max == null ? 0 : max;
    }
    List<Object> args = new ArrayList<>(List.of(subjectId, selected, afterId, snapshot));
    String purchasedFilter = "";
    if (purchased != null) {
      purchasedFilter = " AND l.purchased = ?";
      args.add(purchased);
    }
    args.add(PAGE_SIZE + 1);
    List<Map<String, Object>> rows =
        jdbc.queryForList(
            "SELECT l.id, l.public_id, l.round_no, l.numbers_key, l.recommended_at, l.purchased,"
                + " l.version, l.expires_at, r.published_result_id, r.publication_version,"
                + " e.record_id AS evaluated, e.rank_no, p.prize_per_game"
                + " FROM lotto_record l JOIN lotto_round r ON r.round_no = l.round_no"
                + " LEFT JOIN winning_evaluation e ON e.record_id = l.id"
                + " AND e.result_id = r.published_result_id AND e.record_version = l.version"
                + " LEFT JOIN draw_prize p ON p.result_id = e.result_id AND p.rank_no = e.rank_no"
                + " WHERE l.owner_subject_id = ? AND l.round_no = ?"
                + " AND l.deleted_at IS NULL AND l.id < ? AND l.id <= ?"
                + purchasedFilter
                + rankClause
                + " ORDER BY l.id DESC LIMIT ?",
            args.toArray());
    List<RecordView> items = new ArrayList<>();
    String unpublished =
        rows.isEmpty() || rows.get(0).get("published_result_id") != null
            ? "NOT_PUBLISHED"
            : results.find(selected).map(r -> r.state().name()).orElse("NOT_PUBLISHED");
    for (Map<String, Object> r : rows.subList(0, Math.min(PAGE_SIZE, rows.size()))) {
      items.add(
          new RecordView(
              uuid((byte[]) r.get("public_id")),
              ((Number) r.get("round_no")).intValue(),
              LottoNumbers.fromKey((String) r.get("numbers_key")),
              r.get("recommended_at") != null,
              Boolean.TRUE.equals(r.get("purchased")),
              ((Number) r.get("version")).longValue(),
              instant((LocalDateTime) r.get("expires_at")),
              evaluation(r, unpublished)));
    }
    String next = null;
    if (rows.size() > PAGE_SIZE) {
      long lastId = ((Number) rows.get(PAGE_SIZE - 1).get("id")).longValue();
      next = encode(subjectId, filter, lastId, snapshot);
    }
    RecordPage.EmptyReason reason = null;
    if (items.isEmpty() && cursor == null) {
      if (purchased != null || rank != null) {
        reason = RecordPage.EmptyReason.NO_MATCH;
      } else if (round == null) {
        reason = RecordPage.EmptyReason.NO_CURRENT_RECORDS;
      } else {
        reason = RecordPage.EmptyReason.NO_RECORDS;
      }
    }
    return new RecordPage(selected, items, next, reason);
  }

  private static String rankClause(String rank) {
    if (rank == null) {
      return "";
    }
    if ("LOSING".equals(rank)) {
      return " AND e.record_id IS NOT NULL AND e.rank_no IS NULL";
    }
    if (rank.length() == 1 && rank.charAt(0) >= '1' && rank.charAt(0) <= '5') {
      return " AND e.rank_no = " + rank;
    }
    throw new BusinessException(ErrorCode.VALIDATION_FAILED, "등수 필터 값이 올바르지 않습니다.");
  }

  private static RecordEvaluation evaluation(Map<String, Object> r, String unpublishedState) {
    if (r.get("published_result_id") == null) {
      return new RecordEvaluation(unpublishedState, null, null, null);
    }
    long revision = ((Number) r.get("publication_version")).longValue();
    if (r.get("evaluated") == null) {
      return new RecordEvaluation("PENDING", revision, null, null);
    }
    Integer rank = r.get("rank_no") == null ? null : ((Number) r.get("rank_no")).intValue();
    Long prize =
        r.get("prize_per_game") == null ? null : ((Number) r.get("prize_per_game")).longValue();
    return new RecordEvaluation("EVALUATED", revision, rank, prize);
  }

  private String encode(long subjectId, String filter, long lastId, long snapshot) {
    String body = lastId + "|" + snapshot;
    String payload = ENCODER.encodeToString(body.getBytes(StandardCharsets.UTF_8));
    return payload + "." + ENCODER.encodeToString(sign(subjectId, filter, payload));
  }

  private long[] decode(String cursor, long subjectId, String filter) {
    try {
      String[] parts = cursor.split("\\.", -1);
      if (parts.length != 2
          || !MessageDigest.isEqual(sign(subjectId, filter, parts[0]), DECODER.decode(parts[1]))) {
        throw invalidCursor();
      }
      String[] f = new String(DECODER.decode(parts[0]), StandardCharsets.UTF_8).split("\\|");
      return new long[] {Long.parseLong(f[0]), Long.parseLong(f[1])};
    } catch (IllegalArgumentException | ArrayIndexOutOfBoundsException e) {
      throw invalidCursor();
    }
  }

  private byte[] sign(long subjectId, String filter, String payload) {
    String message = "record-cursor|" + subjectId + "|" + filter + "|" + payload;
    return secrets.commandToken().sign(message.getBytes(StandardCharsets.UTF_8));
  }

  private static BusinessException invalidCursor() {
    return new BusinessException(ErrorCode.VALIDATION_FAILED, "목록을 처음부터 다시 불러와 주세요.");
  }
}
