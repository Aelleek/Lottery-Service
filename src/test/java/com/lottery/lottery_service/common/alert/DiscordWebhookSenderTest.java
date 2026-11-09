package com.lottery.lottery_service.common.alert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Discord 웹훅 전송의 성공·실패 분기를 로컬 HTTP 서버로 확인한다. */
class DiscordWebhookSenderTest {

  private HttpServer server;
  private final AtomicInteger status = new AtomicInteger(204);
  private final List<String> bodies = new ArrayList<>();
  private final List<String> contentTypes = new ArrayList<>();

  @BeforeEach
  void start() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/hook",
        exchange -> {
          bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          contentTypes.add(exchange.getRequestHeaders().getFirst("Content-Type"));
          exchange.sendResponseHeaders(status.get(), -1);
          exchange.close();
        });
    server.start();
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  @Test
  @DisplayName("2xx면 성공이고, 본문은 content 하나만 담은 JSON이다")
  void sendsContentAsJson() throws Exception {
    new DiscordWebhookSender(url()).send("[결과 격리] 1200회 \"승인\" 필요");

    assertThat(contentTypes).containsExactly("application/json");
    assertThat(new ObjectMapper().readTree(bodies.get(0)).properties())
        .singleElement()
        .satisfies(
            e -> {
              assertThat(e.getKey()).isEqualTo("content");
              assertThat(e.getValue().asText()).isEqualTo("[결과 격리] 1200회 \"승인\" 필요");
            });
  }

  @Test
  @DisplayName("2xx가 아니면 실패로 던져 job이 다시 시도하게 하고, 예외 메시지에는 상태 코드만 있다")
  void failsOnNonSuccessStatus() {
    status.set(429);
    String webhook = url();

    assertThatThrownBy(() -> new DiscordWebhookSender(webhook).send("x"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("discord webhook returned 429");
    assertThat(bodies).hasSize(1);
  }

  @Test
  @DisplayName("연결하지 못하면 실패로 던지고, 예외 메시지에 웹훅 주소를 넣지 않는다")
  void failsWhenUnreachable() {
    String webhook = url();
    server.stop(0);

    assertThatThrownBy(() -> new DiscordWebhookSender(webhook).send("x"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("discord webhook failed")
        .satisfies(e -> assertThat(e.getMessage()).doesNotContain("/hook"));
  }

  private String url() {
    return "http://127.0.0.1:" + server.getAddress().getPort() + "/hook";
  }
}
