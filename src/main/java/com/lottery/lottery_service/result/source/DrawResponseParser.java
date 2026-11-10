package com.lottery.lottery_service.result.source;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lottery.lottery_service.common.number.LottoNumber;
import com.lottery.lottery_service.common.number.LottoNumbers;
import com.lottery.lottery_service.result.domain.WinningNumbers;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 동행복권 결과 응답({@code lt645/selectPstLt645InfoNew.do})을 검증해 읽는다(03 3절, 05 6절 2번). 응답의 {@code
 * data.list}는 요청 회차 주변 최대 10회차라 {@code ltEpsd}가 요청 회차인 항목을 고른다. 빈 목록이거나 모든 항목이 요청 회차보다 앞이면 미발표다.
 * HTTP 200이어도 로그인 화면 HTML, 다른 형식, 범위 밖 번호, 중복, 보너스 겹침이면 실패로 본다.
 */
public final class DrawResponseParser {

  /** 받아들이는 응답 크기 상한. 정상 응답(10회차)은 6~7KB다. */
  public static final int MAX_BYTES = 32 * 1024;

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private DrawResponseParser() {}

  /** 요청한 회차의 응답을 읽는다. */
  public static ParsedDraw parse(int requestedRound, String body) {
    if (body == null || body.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
      return new ParsedDraw.Invalid("SIZE");
    }
    JsonNode json;
    try {
      json = MAPPER.readTree(body);
    } catch (Exception e) {
      return new ParsedDraw.Invalid("NOT_JSON");
    }
    if (json == null || !json.isObject()) {
      return new ParsedDraw.Invalid("NOT_JSON");
    }
    JsonNode list = json.path("data").path("list");
    if (!list.isArray()) {
      return new ParsedDraw.Invalid("SHAPE");
    }
    JsonNode item = null;
    boolean newerSeen = false;
    boolean malformed = false;
    for (JsonNode candidate : list) {
      JsonNode round = candidate.path("ltEpsd");
      if (!round.isInt()) {
        malformed = true;
      } else if (round.asInt() == requestedRound) {
        item = candidate;
      } else if (round.asInt() > requestedRound) {
        newerSeen = true;
      }
    }
    if (item == null && malformed) {
      return new ParsedDraw.Invalid("ROUND");
    }
    if (item == null) {
      return newerSeen ? new ParsedDraw.Invalid("ROUND") : new ParsedDraw.NotAnnounced();
    }
    return parseItem(requestedRound, item);
  }

  private static ParsedDraw parseItem(int requestedRound, JsonNode item) {
    List<Integer> numbers = new ArrayList<>();
    for (int i = 1; i <= LottoNumbers.SIZE; i++) {
      JsonNode n = item.path("tm" + i + "WnNo");
      if (!n.isInt()) {
        return new ParsedDraw.Invalid("NUMBERS");
      }
      numbers.add(n.asInt());
    }
    JsonNode bonus = item.path("bnsWnNo");
    if (!bonus.isInt()) {
      return new ParsedDraw.Invalid("BONUS");
    }
    WinningNumbers winning;
    try {
      winning = new WinningNumbers(LottoNumbers.of(numbers), LottoNumber.of(bonus.asInt()));
    } catch (IllegalArgumentException e) {
      return new ParsedDraw.Invalid("NUMBERS");
    }
    List<RankPrize> prizes = new ArrayList<>();
    for (int rank = 1; rank <= OfficialDraw.UPPER_RANKS; rank++) {
      prizes.add(
          new RankPrize(
              rank,
              positive(item.path("rnk" + rank + "WnAmt")),
              nonNegative(item.path("rnk" + rank + "WnNope"))));
    }
    return new ParsedDraw.Valid(
        new OfficialDraw(requestedRound, winning, prizes, OfficialDraw.sha256(item.toString())));
  }

  private static Long positive(JsonNode node) {
    Long value = nonNegative(node);
    return value == null || value == 0 ? null : value;
  }

  private static Long nonNegative(JsonNode node) {
    if (!node.isIntegralNumber() || !node.canConvertToLong() || node.asLong() < 0) {
      return null;
    }
    return node.asLong();
  }
}
