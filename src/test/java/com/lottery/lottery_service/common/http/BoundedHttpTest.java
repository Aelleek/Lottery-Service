package com.lottery.lottery_service.common.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lottery.lottery_service.support.SlowBodyServer;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BoundedHttpTest {

  private final HttpClient http = HttpClient.newHttpClient();
  private HttpServer server;

  @BeforeEach
  void start() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/ok",
        exchange -> {
          byte[] out = "hello".getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(201, out.length);
          exchange.getResponseBody().write(out);
          exchange.close();
        });
    server.createContext(
        "/chunked",
        exchange -> {
          exchange.sendResponseHeaders(200, 0);
          try (OutputStream out = exchange.getResponseBody()) {
            for (int i = 0; i < 64; i++) {
              out.write(new byte[1024]);
            }
          }
        });
    server.start();
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  @Test
  @DisplayName("상한 안의 응답은 상태와 본문을 그대로 준다")
  void returnsBody() throws Exception {
    BoundedHttp.Response response =
        BoundedHttp.send(http, request("/ok"), 5, Duration.ofSeconds(5));

    assertThat(response.status()).isEqualTo(201);
    assertThat(new String(response.body(), StandardCharsets.UTF_8)).isEqualTo("hello");
  }

  @Test
  @DisplayName("길이를 미리 알리지 않은 본문도 상한을 넘으면 실패한다")
  void rejectsOversizedChunkedBody() {
    assertThatThrownBy(
            () -> BoundedHttp.send(http, request("/chunked"), 4096, Duration.ofSeconds(5)))
        .isInstanceOf(BoundedHttp.TooLargeException.class);
    assertThatThrownBy(() -> BoundedHttp.send(http, request("/ok"), 4, Duration.ofSeconds(5)))
        .isInstanceOf(BoundedHttp.TooLargeException.class);
  }

  @Test
  @DisplayName("본문이 시간 상한 안에 끝나지 않으면 그 시각에 실패한다")
  void stopsAtTotalDeadline() throws Exception {
    HttpServer slow = SlowBodyServer.start(1_000);
    try {
      long started = System.nanoTime();
      HttpRequest request =
          HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + slow.getAddress().getPort()))
              .build();

      assertThatThrownBy(() -> BoundedHttp.send(http, request, 4096, Duration.ofSeconds(1)))
          .isInstanceOf(HttpTimeoutException.class);
      assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(4));
    } finally {
      slow.stop(0);
    }
  }

  private HttpRequest request(String path) {
    return HttpRequest.newBuilder(
            URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path))
        .build();
  }
}
