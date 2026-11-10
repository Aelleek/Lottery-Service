package com.lottery.lottery_service.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/** 실제 Tomcat으로 앞단 전달 헤더를 믿는 조건을 확인한다(07 5절 5번, 2026-11-09 Q6·Q22). MockMvc는 Tomcat 밸브를 거치지 않는다. */
class ForwardedHeadersTest {

  private static final HttpClient HTTP = HttpClient.newHttpClient();

  static HttpResponse<String> get(int port, String forwardedFor) throws Exception {
    return HTTP.send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/session"))
            .header("X-Forwarded-For", forwardedFor)
            .header("X-Forwarded-Proto", "https")
            .GET()
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  /** 연결 주소가 설정한 앞단이면 헤더를 믿는다. */
  @Nested
  @SpringBootTest(
      webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
      properties = {
        "server.forward-headers-strategy=native",
        "server.tomcat.remoteip.internal-proxies=127\\\\.0\\\\.0\\\\.1"
      })
  class TrustedProxy {

    @LocalServerPort int port;

    @Test
    @DisplayName("앞단이 붙인 https는 HSTS를 붙이고, 요청 제한은 앞단이 붙인 IP별로 센다")
    void usesForwardedValues() throws Exception {
      HttpResponse<String> first = get(port, "203.0.113.10");
      assertThat(first.headers().firstValue("Strict-Transport-Security")).isPresent();

      int limited = 0;
      for (int i = 0; i < 300; i++) {
        if (get(port, "203.0.113.10").statusCode() == 429) {
          limited++;
        }
      }
      assertThat(limited).isPositive();
      assertThat(get(port, "203.0.113.11").statusCode()).isNotEqualTo(429);
    }
  }

  /** 믿을 앞단을 정하지 않으면 헤더를 믿지 않는다. */
  @Nested
  @SpringBootTest(
      webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
      properties = {
        "server.forward-headers-strategy=native",
        "server.tomcat.remoteip.internal-proxies="
      })
  class NoTrustedProxy {

    @LocalServerPort int port;

    @Test
    @DisplayName("요청이 보낸 X-Forwarded-Proto로 HSTS를 붙이지 않고, X-Forwarded-For를 바꿔도 같은 IP로 센다")
    void ignoresForwardedValues() throws Exception {
      assertThat(get(port, "198.51.100.1").headers().firstValue("Strict-Transport-Security"))
          .isEmpty();

      int limited = 0;
      for (int i = 0; i < 300; i++) {
        if (get(port, "198.51.100." + (i % 250 + 2)).statusCode() == 429) {
          limited++;
        }
      }
      assertThat(limited).isPositive();
    }
  }
}
