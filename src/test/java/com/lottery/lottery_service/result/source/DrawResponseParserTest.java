package com.lottery.lottery_service.result.source;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** 2026-11-10에 받은 실제 응답 표본(1249회, 1회, 추첨 전 1250회)으로 결과 응답 읽기를 확인한다. */
class DrawResponseParserTest {

  static final String ROUND_1249 = sample("round-1249.json");
  static final String ROUND_1 = sample("round-1.json");
  static final String ROUND_1250 = sample("round-1250-unpublished.json");

  private static final String ITEM_1249 =
      "{\"ltEpsd\":1249,\"tm1WnNo\":2,\"tm2WnNo\":11,\"tm3WnNo\":16,\"tm4WnNo\":25,"
          + "\"tm5WnNo\":34,\"tm6WnNo\":35,\"bnsWnNo\":14,\"rnk1WnNope\":14,"
          + "\"rnk1WnAmt\":2025045509,\"rnk2WnNope\":93,\"rnk2WnAmt\":50807594,"
          + "\"rnk3WnNope\":3550,\"rnk3WnAmt\":1331016}";

  @Test
  @DisplayName("실제 1249회 응답은 첫 항목을 골라 번호, 보너스, 1~3등 금액·당첨 수를 읽는다")
  void latestSample() {
    OfficialDraw draw = valid(1249, ROUND_1249);

    assertThat(draw.roundNo()).isEqualTo(1249);
    assertThat(draw.winning().main().key()).isEqualTo("02 11 16 25 34 35");
    assertThat(draw.winning().bonus().value()).isEqualTo(14);
    assertThat(draw.upperPrizes())
        .containsExactly(
            new RankPrize(1, 2_025_045_509L, 14L),
            new RankPrize(2, 50_807_594L, 93L),
            new RankPrize(3, 1_331_016L, 3550L));
    assertThat(draw.sourceHash()).hasSize(32);
  }

  @Test
  @DisplayName("실제 1회 응답은 10~1회 내림차순이라 마지막 항목을 고르고, 1등 0명·0원은 금액 확인 중이다")
  void firstRoundSample() {
    OfficialDraw draw = valid(1, ROUND_1);

    assertThat(draw.winning().main().key()).isEqualTo("10 23 29 33 37 40");
    assertThat(draw.winning().bonus().value()).isEqualTo(16);
    assertThat(draw.prize(1)).isEqualTo(new RankPrize(1, null, 0L));
    assertThat(draw.prize(2)).isEqualTo(new RankPrize(2, 143_934_100L, 1L));
    assertThat(draw.prize(3)).isEqualTo(new RankPrize(3, 5_140_500L, 28L));
  }

  @Test
  @DisplayName("같은 응답 안의 다른 회차도 ltEpsd로 골라 읽는다")
  void picksRequestedRoundFromList() {
    assertThat(valid(1245, ROUND_1249).roundNo()).isEqualTo(1245);
    assertThat(valid(1245, ROUND_1249).winning().main().key())
        .isNotEqualTo(valid(1249, ROUND_1249).winning().main().key());
  }

  @Test
  @DisplayName("추첨 전 회차의 빈 목록(실제 1250회 응답)과 앞 회차만 있는 목록은 미발표다")
  void notAnnounced() {
    assertThat(DrawResponseParser.parse(1250, ROUND_1250))
        .isInstanceOf(ParsedDraw.NotAnnounced.class);
    assertThat(DrawResponseParser.parse(1250, ROUND_1249))
        .isInstanceOf(ParsedDraw.NotAnnounced.class);
  }

  @Test
  @DisplayName("요청 회차가 없는데 뒤 회차가 있으면 다른 회차 응답으로 보고 실패다")
  void missingRoundWithNewerItems() {
    assertThat(DrawResponseParser.parse(1239, ROUND_1249))
        .isEqualTo(new ParsedDraw.Invalid("ROUND"));
  }

  @Test
  @DisplayName("2·3등 금액이 없거나 0이거나 음수면 확인 중이고, 번호는 그대로 읽는다")
  void missingUpperPrizes() {
    String body =
        wrap(
            ITEM_1249
                .replace("\"rnk2WnAmt\":50807594", "\"rnk2WnAmt\":0")
                .replace(",\"rnk3WnAmt\":1331016", "")
                .replace("\"rnk1WnAmt\":2025045509", "\"rnk1WnAmt\":-5")
                .replace("\"rnk1WnNope\":14", "\"rnk1WnNope\":-1"));

    OfficialDraw draw = valid(1249, body);

    assertThat(draw.prize(1)).isEqualTo(new RankPrize(1, null, null));
    assertThat(draw.prize(2)).isEqualTo(new RankPrize(2, null, 93L));
    assertThat(draw.prize(3)).isEqualTo(new RankPrize(3, null, 3550L));
    assertThat(draw.winning().main().key()).isEqualTo("02 11 16 25 34 35");
  }

  @ParameterizedTest(name = "{0}")
  @CsvSource(
      delimiter = '|',
      value = {
        "로그인 화면 HTML|<html><body>login</body></html>|NOT_JSON",
        "옛 형식 응답|{\"returnValue\":\"success\",\"drwNo\":1249}|SHAPE",
        "목록이 배열이 아님|{\"data\":{\"list\":{}}}|SHAPE",
        "회차가 숫자가 아님|{\"data\":{\"list\":[{\"ltEpsd\":\"1249\"}]}}|ROUND",
        "번호 누락|{\"data\":{\"list\":[{\"ltEpsd\":1249,\"tm1WnNo\":1}]}}|NUMBERS"
      })
  @DisplayName("HTTP 200이어도 믿을 수 없는 응답은 실패로 본다")
  void invalidShapes(String name, String body, String reason) {
    assertThat(DrawResponseParser.parse(1249, body)).isEqualTo(new ParsedDraw.Invalid(reason));
  }

  @Test
  @DisplayName("범위 밖 번호, 중복 번호, 본번호와 겹치는 보너스, 너무 큰 응답은 실패다")
  void invalidNumbers() {
    assertThat(parse(ITEM_1249.replace("\"tm6WnNo\":35", "\"tm6WnNo\":46")))
        .isEqualTo(new ParsedDraw.Invalid("NUMBERS"));
    assertThat(parse(ITEM_1249.replace("\"tm6WnNo\":35", "\"tm6WnNo\":34")))
        .isEqualTo(new ParsedDraw.Invalid("NUMBERS"));
    assertThat(parse(ITEM_1249.replace("\"bnsWnNo\":14", "\"bnsWnNo\":35")))
        .isEqualTo(new ParsedDraw.Invalid("NUMBERS"));
    assertThat(parse(ITEM_1249.replace("\"bnsWnNo\":14", "\"bnsWnNo\":\"14\"")))
        .isEqualTo(new ParsedDraw.Invalid("BONUS"));
    assertThat(DrawResponseParser.parse(1249, " ".repeat(DrawResponseParser.MAX_BYTES + 1)))
        .isEqualTo(new ParsedDraw.Invalid("SIZE"));
  }

  @Test
  @DisplayName("금액만 다른 응답은 의미 요약이 같고, 번호가 다르면 다르다")
  void semanticHash() {
    OfficialDraw a = valid(1249, wrap(ITEM_1249));
    OfficialDraw b = valid(1249, wrap(ITEM_1249.replace("1331016", "1331017")));
    OfficialDraw c = valid(1249, wrap(ITEM_1249.replace("\"bnsWnNo\":14", "\"bnsWnNo\":15")));

    assertThat(a.semanticHash()).isEqualTo(b.semanticHash()).isNotEqualTo(c.semanticHash());
    assertThat(a.sourceHash()).isNotEqualTo(b.sourceHash());
  }

  private static ParsedDraw parse(String item) {
    return DrawResponseParser.parse(1249, wrap(item));
  }

  private static OfficialDraw valid(int round, String body) {
    return ((ParsedDraw.Valid) DrawResponseParser.parse(round, body)).draw();
  }

  static String wrap(String item) {
    return "{\"resultCode\":null,\"resultMessage\":null,\"data\":{\"list\":[" + item + "]}}";
  }

  static String sample(String name) {
    try (InputStream in = DrawResponseParserTest.class.getResourceAsStream("/dhlottery/" + name)) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
