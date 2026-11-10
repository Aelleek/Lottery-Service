package com.lottery.lottery_service.result.source;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class DrawResponseParserTest {

  private static final String VALID =
      "{\"totSellamnt\":111840714000,\"returnValue\":\"success\",\"drwNoDate\":\"2024-03-23\","
          + "\"firstWinamnt\":1672663500,\"drwtNo6\":44,\"drwtNo4\":30,\"firstPrzwnerCo\":16,"
          + "\"drwtNo5\":42,\"bnusNo\":25,\"firstAccumamnt\":26762616000,\"drwNo\":1112,"
          + "\"drwtNo2\":20,\"drwtNo3\":26,\"drwtNo1\":16}";

  @Test
  @DisplayName("정상 응답은 번호를 오름차순으로, 보너스와 1등 금액·당첨 수를 함께 읽는다")
  void valid() {
    ParsedDraw parsed = DrawResponseParser.parse(1112, VALID);

    OfficialDraw draw = ((ParsedDraw.Valid) parsed).draw();
    assertThat(draw.winning().main().key()).isEqualTo("16 20 26 30 42 44");
    assertThat(draw.winning().bonus().value()).isEqualTo(25);
    assertThat(draw.firstPrizePerGame()).isEqualTo(1_672_663_500L);
    assertThat(draw.firstWinnerCount()).isEqualTo(16);
    assertThat(draw.sourceHash()).hasSize(32);
  }

  @Test
  @DisplayName("1등 금액·당첨 수가 음수면 믿지 않고 비워 둔다(번호는 그대로 읽음)")
  void negativeAmountsIgnored() {
    String body =
        VALID
            .replace("\"firstWinamnt\":1672663500", "\"firstWinamnt\":-5")
            .replace("\"firstPrzwnerCo\":16", "\"firstPrzwnerCo\":-1");

    OfficialDraw draw = ((ParsedDraw.Valid) DrawResponseParser.parse(1112, body)).draw();

    assertThat(draw.firstPrizePerGame()).isNull();
    assertThat(draw.firstWinnerCount()).isNull();
    assertThat(draw.winning().main().key()).isEqualTo("16 20 26 30 42 44");
  }

  @Test
  @DisplayName("미발표(returnValue=fail)는 수집 실패와 구분한다")
  void notAnnounced() {
    assertThat(DrawResponseParser.parse(1300, "{\"returnValue\":\"fail\"}"))
        .isInstanceOf(ParsedDraw.NotAnnounced.class);
  }

  @ParameterizedTest(name = "{0}")
  @CsvSource(
      delimiter = '|',
      value = {
        "로그인 화면 HTML|<html><body>login</body></html>|NOT_JSON",
        "다른 회차|{\"returnValue\":\"success\",\"drwNo\":1111}|ROUND",
        "알 수 없는 상태|{\"returnValue\":\"maintenance\"}|STATUS",
        "번호 누락|{\"returnValue\":\"success\",\"drwNo\":1112,\"drwtNo1\":1}|NUMBERS"
      })
  @DisplayName("HTTP 200이어도 믿을 수 없는 응답은 실패로 본다")
  void invalidShapes(String name, String body, String reason) {
    assertThat(DrawResponseParser.parse(1112, body)).isEqualTo(new ParsedDraw.Invalid(reason));
  }

  @Test
  @DisplayName("범위 밖 번호, 중복 번호, 본번호와 겹치는 보너스, 너무 큰 응답은 실패다")
  void invalidNumbers() {
    assertThat(DrawResponseParser.parse(1112, VALID.replace("\"drwtNo6\":44", "\"drwtNo6\":46")))
        .isEqualTo(new ParsedDraw.Invalid("NUMBERS"));
    assertThat(DrawResponseParser.parse(1112, VALID.replace("\"drwtNo6\":44", "\"drwtNo6\":42")))
        .isEqualTo(new ParsedDraw.Invalid("NUMBERS"));
    assertThat(DrawResponseParser.parse(1112, VALID.replace("\"bnusNo\":25", "\"bnusNo\":44")))
        .isEqualTo(new ParsedDraw.Invalid("NUMBERS"));
    assertThat(DrawResponseParser.parse(1112, VALID.replace("\"bnusNo\":25", "\"bnusNo\":\"25\"")))
        .isEqualTo(new ParsedDraw.Invalid("BONUS"));
    assertThat(DrawResponseParser.parse(1112, " ".repeat(DrawResponseParser.MAX_BYTES + 1)))
        .isEqualTo(new ParsedDraw.Invalid("SIZE"));
  }

  @Test
  @DisplayName("금액만 다른 응답은 의미 요약이 같고, 번호가 다르면 다르다")
  void semanticHash() {
    OfficialDraw a = ((ParsedDraw.Valid) DrawResponseParser.parse(1112, VALID)).draw();
    OfficialDraw b =
        ((ParsedDraw.Valid)
                DrawResponseParser.parse(1112, VALID.replace("1672663500", "1672663501")))
            .draw();
    OfficialDraw c =
        ((ParsedDraw.Valid)
                DrawResponseParser.parse(1112, VALID.replace("\"bnusNo\":25", "\"bnusNo\":24")))
            .draw();

    assertThat(a.semanticHash()).isEqualTo(b.semanticHash()).isNotEqualTo(c.semanticHash());
    assertThat(a.sourceHash()).isNotEqualTo(b.sourceHash());
  }
}
