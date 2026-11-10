package com.lottery.lottery_service.result.source;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 가짜 결과 서버로 외부 호출의 상태 코드·주소 이동·크기 상한 처리를 확인한다. 실제 원천 표본은 출시 조건으로 따로 확인한다. */
class DhlotteryDrawSourceTest {

  private final AtomicInteger status = new AtomicInteger(200);
  private final AtomicReference<String> body = new AtomicReference<>("");
  private final AtomicReference<String> location = new AtomicReference<>();
  private final AtomicReference<String> requested = new AtomicReference<>();
  private HttpServer server;
  private DhlotteryDrawSource source;

  @BeforeEach
  void start() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          requested.set(exchange.getRequestURI().toString());
          if (location.get() != null) {
            exchange.getResponseHeaders().add("Location", location.get());
          }
          byte[] out = body.get().getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(status.get(), out.length == 0 ? -1 : out.length);
          if (out.length > 0) {
            exchange.getResponseBody().write(out);
          }
          exchange.close();
        });
    server.start();
    source =
        new DhlotteryDrawSource(
            "http://127.0.0.1:"
                + server.getAddress().getPort()
                + "/lt645/selectPstLt645InfoNew.do?srchDir=center&srchLtEpsd=");
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  @Test
  @DisplayName("회차를 주소 끝에 붙여 부르고, 200 JSON은 파싱하며 빈 목록은 NotAnnounced다")
  void parsesBody() {
    body.set(DrawResponseParserTest.ROUND_1249);
    ParsedDraw parsed = source.fetch(1249);

    assertThat(requested.get())
        .isEqualTo("/lt645/selectPstLt645InfoNew.do?srchDir=center&srchLtEpsd=1249");
    assertThat(((ParsedDraw.Valid) parsed).draw().winning().main().key())
        .isEqualTo("02 11 16 25 34 35");
    body.set(DrawResponseParserTest.ROUND_1250);
    assertThat(source.fetch(1250)).isInstanceOf(ParsedDraw.NotAnnounced.class);
  }

  @Test
  @DisplayName("주소 이동(302)은 따르지 않고 HTTP_302 실패, 500도 실패다")
  void redirectAndErrors() {
    status.set(302);
    location.set("https://www.dhlottery.co.kr/");
    assertThat(source.fetch(1200)).isEqualTo(new ParsedDraw.Invalid("HTTP_302"));
    location.set(null);
    status.set(500);
    assertThat(source.fetch(1200)).isEqualTo(new ParsedDraw.Invalid("HTTP_500"));
  }

  @Test
  @DisplayName("크기 상한(32KB)을 넘는 본문은 다 읽지 않고 SIZE 실패다")
  void oversizedBody() {
    body.set("x".repeat(DrawResponseParser.MAX_BYTES + 100));
    assertThat(source.fetch(1200)).isEqualTo(new ParsedDraw.Invalid("SIZE"));
  }

  @Test
  @DisplayName("연결할 수 없으면 NETWORK 실패다")
  void unreachable() {
    server.stop(0);
    assertThat(source.fetch(1200)).isEqualTo(new ParsedDraw.Invalid("NETWORK"));
  }
}
