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
 * 동행복권 결과 API 응답(`common.do?method=getLottoNumber`)을 검증해 읽는다(03 3절, 05 6절 2번). HTTP 200이어도 로그인 화면
 * HTML, 다른 회차, 범위 밖 번호, 중복, 보너스 겹침이면 실패로 본다.
 */
public final class DrawResponseParser {

  /** 받아들이는 응답 크기 상한. 정상 응답은 수백 바이트다. */
  public static final int MAX_BYTES = 16 * 1024;

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
    String status = json.path("returnValue").asText("");
    if ("fail".equals(status)) {
      return new ParsedDraw.NotAnnounced();
    }
    if (!"success".equals(status)) {
      return new ParsedDraw.Invalid("STATUS");
    }
    if (!json.path("drwNo").canConvertToInt() || json.path("drwNo").asInt() != requestedRound) {
      return new ParsedDraw.Invalid("ROUND");
    }
    List<Integer> numbers = new ArrayList<>();
    for (int i = 1; i <= LottoNumbers.SIZE; i++) {
      JsonNode n = json.path("drwtNo" + i);
      if (!n.isInt()) {
        return new ParsedDraw.Invalid("NUMBERS");
      }
      numbers.add(n.asInt());
    }
    JsonNode bonus = json.path("bnusNo");
    if (!bonus.isInt()) {
      return new ParsedDraw.Invalid("BONUS");
    }
    WinningNumbers winning;
    try {
      winning = new WinningNumbers(LottoNumbers.of(numbers), LottoNumber.of(bonus.asInt()));
    } catch (IllegalArgumentException e) {
      return new ParsedDraw.Invalid("NUMBERS");
    }
    Long prize = nonNegative(json.path("firstWinamnt"));
    Long winners = nonNegative(json.path("firstPrzwnerCo"));
    return new ParsedDraw.Valid(
        new OfficialDraw(requestedRound, winning, prize, winners, OfficialDraw.sha256(body)));
  }

  private static Long nonNegative(JsonNode node) {
    if (!node.canConvertToLong() || node.asLong() < 0) {
      return null;
    }
    return node.asLong();
  }
}
