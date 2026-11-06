package com.lottery.lottery_service.notification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 결과 알림 한 건이 회원에게 알린 내용의 요약({@code delivery_message.communicated_summary}, 04 7절). 번호와 전화번호는 담지 않고,
 * 알린 게시본과 대상 기록마다 등수와 1게임 금액만 담는다. 정정 뒤 영향 회원 수를 셀 때 새 판정과 비교한다(05 8.5절 5번).
 *
 * @param resultId 알릴 때의 게시본 ID
 * @param items 대상 기록마다 등수와 금액. 등수, 금액 순으로 정렬
 */
public record CommunicatedSummary(long resultId, List<Item> items) {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /**
   * 기록 하나의 안내.
   *
   * @param rank 등수(1~5)
   * @param prizePerGame 1게임 금액. 알릴 때 "확인 중"이었으면 {@code null}
   */
  public record Item(int rank, Long prizePerGame) {}

  /** 정렬해 만든다. */
  public CommunicatedSummary {
    items =
        items.stream()
            .sorted(
                Comparator.comparingInt(Item::rank)
                    .thenComparing(
                        Item::prizePerGame, Comparator.nullsFirst(Comparator.naturalOrder())))
            .toList();
  }

  /** 회원의 현재 대상 기록과 회차의 현재 게시본 금액으로 만든다. 대상이 아니면 항목이 없다. */
  public static CommunicatedSummary current(
      JdbcTemplate jdbc, int roundNo, NotificationTarget target) {
    List<Map<String, Object>> rows =
        jdbc.queryForList(
            "SELECT r.published_result_id, p.rank_no, p.prize_per_game FROM lotto_round r"
                + " LEFT JOIN draw_prize p ON p.result_id = r.published_result_id"
                + " WHERE r.round_no = ?",
            roundNo);
    long resultId =
        rows.isEmpty() || rows.get(0).get("published_result_id") == null
            ? 0
            : ((Number) rows.get(0).get("published_result_id")).longValue();
    Long[] prizes = new Long[6];
    for (Map<String, Object> row : rows) {
      if (row.get("rank_no") != null && row.get("prize_per_game") != null) {
        prizes[((Number) row.get("rank_no")).intValue()] =
            ((Number) row.get("prize_per_game")).longValue();
      }
    }
    List<Item> items = new ArrayList<>();
    if (target != null) {
      for (NotificationTarget.Item item : target.records()) {
        int rank = item.rank().ordinal() + 1;
        items.add(new Item(rank, prizes[rank]));
      }
    }
    return new CommunicatedSummary(resultId, items);
  }

  /** 저장된 JSON을 읽는다. */
  public static CommunicatedSummary parse(String json) {
    try {
      JsonNode root = MAPPER.readTree(json);
      List<Item> items = new ArrayList<>();
      for (JsonNode item : root.path("items")) {
        JsonNode prize = item.path("prizePerGame");
        items.add(
            new Item(
                item.path("rank").asInt(),
                prize.isNull() || prize.isMissingNode() ? null : prize.asLong()));
      }
      return new CommunicatedSummary(root.path("resultId").asLong(), items);
    } catch (Exception e) {
      throw new IllegalStateException("invalid communicated summary", e);
    }
  }

  /** 저장할 JSON. */
  public String toJson() {
    try {
      return MAPPER.writeValueAsString(this);
    } catch (Exception e) {
      throw new IllegalStateException("cannot write communicated summary", e);
    }
  }

  /**
   * 알린 내용과 지금 판정이 달라졌는지. 등수가 바뀌었거나, 알린 금액이 있었는데 지금 금액과 다르면 바뀐 것이다. 알릴 때 "확인 중"이던 금액이 확정된 것은 바뀐 것이
   * 아니다(05 8.5절 6번).
   */
  public boolean differsFrom(CommunicatedSummary now) {
    if (items.size() != now.items.size()) {
      return true;
    }
    for (int i = 0; i < items.size(); i++) {
      Item told = items.get(i);
      Item current = now.items.get(i);
      if (told.rank() != current.rank()) {
        return true;
      }
      if (told.prizePerGame() != null
          && !Objects.equals(told.prizePerGame(), current.prizePerGame())) {
        return true;
      }
    }
    return false;
  }
}
