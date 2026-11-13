package com.lottery.lottery_service.notification.solapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lottery.lottery_service.notification.MessageLookup;
import com.lottery.lottery_service.notification.ProviderStatus;
import com.lottery.lottery_service.notification.SendOutcome;
import com.lottery.lottery_service.support.SlowBodyServer;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/** 가짜 SOLAPI 서버로 요청 형식과 응답 해석을 확인한다. 실제 발송 왕복은 출시 조건으로 따로 확인한다(08 3절). */
@ExtendWith(OutputCaptureExtension.class)
class SolapiClientTest {

  private final AtomicInteger status = new AtomicInteger(200);
  private final AtomicReference<String> responseBody = new AtomicReference<>("{}");
  private final Map<String, String> pages = new java.util.concurrent.ConcurrentHashMap<>();
  private final List<String> requests = new ArrayList<>();
  private final List<String> paths = new ArrayList<>();
  private final List<String> auth = new ArrayList<>();
  private HttpServer server;
  private SolapiClient client;

  @BeforeEach
  void start() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          paths.add(exchange.getRequestURI().toString());
          auth.add(exchange.getRequestHeaders().getFirst("Authorization"));
          requests.add(
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          String query = exchange.getRequestURI().getRawQuery();
          String startKey =
              query != null && query.contains("startKey=")
                  ? query.substring(query.indexOf("startKey=") + 9)
                  : "";
          String chosen = pages.getOrDefault(startKey, responseBody.get());
          int code = "ERROR".equals(chosen) ? 500 : status.get();
          byte[] out = ("ERROR".equals(chosen) ? "{}" : chosen).getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(code, out.length == 0 ? -1 : out.length);
          if (out.length > 0) {
            exchange.getResponseBody().write(out);
          }
          exchange.close();
        });
    server.start();
    client =
        new SolapiClient(
            new SolapiProperties(
                "http://127.0.0.1:" + server.getAddress().getPort(),
                "KEY",
                "SECRET",
                "01000000000",
                "KA01PF000"),
            Clock.fixed(Instant.parse("2026-11-08T15:30:00Z"), ZoneOffset.UTC));
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  @Test
  @DisplayName("알림톡 요청은 ATA·채널·템플릿·치환값·요청 키를 담고 문자 대체 발송을 끈다. 2000이면 접수")
  void sendAccepted() throws Exception {
    responseBody.set(
        "{\"messageList\":[{\"messageId\":\"M4V1\",\"statusCode\":\"2000\",\"statusMessage\":\"ok\"}],"
            + "\"failedMessageList\":[],\"groupInfo\":{}}");

    SendOutcome outcome =
        client.sendAlimtalk("01012345678", "KA01TP1", Map.of("#{round}", "1200"), "ab12");

    assertThat(outcome).isEqualTo(new SendOutcome.Accepted("M4V1"));
    assertThat(paths).containsExactly("/messages/v4/send-many/fast");
    assertThat(auth.get(0)).startsWith("HMAC-SHA256 apiKey=KEY, date=2026-11-08T15:30:00Z, salt=");
    JsonNode message = new ObjectMapper().readTree(requests.get(0)).path("messages").path(0);
    assertThat(message.path("type").asText()).isEqualTo("ATA");
    assertThat(message.path("from").asText()).isEqualTo("01000000000");
    assertThat(message.path("kakaoOptions").path("pfId").asText()).isEqualTo("KA01PF000");
    assertThat(message.path("kakaoOptions").path("templateId").asText()).isEqualTo("KA01TP1");
    assertThat(message.path("kakaoOptions").path("disableSms").asBoolean()).isTrue();
    assertThat(message.path("kakaoOptions").path("variables").path("#{round}").asText())
        .isEqualTo("1200");
    assertThat(message.path("customFields").path("requestKey").asText()).isEqualTo("ab12");
    assertThat(requests.get(0)).doesNotContain("SECRET");
  }

  @Test
  @DisplayName("접수 실패 1xxx는 거절, 그 밖의 코드는 미접수, 4xx는 미접수, 5xx·이상 응답은 알 수 없음")
  void sendFailures() {
    responseBody.set(
        "{\"messageList\":[],\"failedMessageList\":[{\"statusCode\":\"1010\"}],\"groupInfo\":{}}");
    assertThat(send()).isEqualTo(new SendOutcome.Rejected("SOLAPI_1010"));
    responseBody.set(
        "{\"messageList\":[],\"failedMessageList\":[{\"statusCode\":\"2024\"}],\"groupInfo\":{}}");
    assertThat(send()).isEqualTo(new SendOutcome.NotAccepted("SOLAPI_2024"));

    status.set(401);
    responseBody.set("{\"errorCode\":\"InvalidApiKey\"}");
    assertThat(send()).isEqualTo(new SendOutcome.NotAccepted("HTTP_401"));
    status.set(503);
    assertThat(send()).isInstanceOf(SendOutcome.Unknown.class);
    status.set(200);
    responseBody.set("<html>login</html>");
    assertThat(send()).isInstanceOf(SendOutcome.Unknown.class);
    responseBody.set("{}");
    assertThat(send()).isInstanceOf(SendOutcome.Unknown.class);
  }

  @Test
  @DisplayName("조회는 4000 전달, 2000·3000 접수, 다른 코드 실패, 요청 키가 다르면 찾지 못함, 실패 응답은 찾지 못함")
  void lookup() {
    SolapiMessageLookup lookup = new SolapiMessageLookup(client);
    byte[] key = {(byte) 0xab, 0x12};
    Instant at = Instant.parse("2026-11-08T12:31:00Z");

    responseBody.set(
        "{\"messageList\":{\"M4V1\":{\"messageId\":\"M4V1\",\"statusCode\":\"4000\"}}}");
    assertThat(lookup.lookup(new MessageLookup.UnknownMessage(1, key, "M4V1", at)))
        .isEqualTo(new ProviderStatus.Delivered("M4V1"));
    assertThat(paths.get(paths.size() - 1)).isEqualTo("/messages/v4/list?messageId=M4V1");

    responseBody.set(
        "{\"messageList\":{"
            + "\"M4V8\":{\"messageId\":\"M4V8\",\"statusCode\":\"4000\",\"customFields\":{\"requestKey\":\"ffff\"}},"
            + "\"M4V9\":{\"messageId\":\"M4V9\",\"statusCode\":\"3000\",\"customFields\":{\"requestKey\":\"ab12\"}}}}");
    assertThat(lookup.lookup(new MessageLookup.UnknownMessage(1, key, null, at)))
        .isEqualTo(new ProviderStatus.Accepted("M4V9"));
    assertThat(paths.get(paths.size() - 1))
        .startsWith("/messages/v4/list?dateType=CREATED&startDate=2026-11-08T12%3A26%3A00Z");

    responseBody.set(
        "{\"messageList\":{\"M4V9\":{\"messageId\":\"M4V9\",\"statusCode\":\"3040\",\"customFields\":{\"requestKey\":\"ab12\"}}}}");
    assertThat(lookup.lookup(new MessageLookup.UnknownMessage(1, key, null, at)))
        .isEqualTo(new ProviderStatus.Failed("SOLAPI_3040"));

    responseBody.set("{\"messageList\":{}}");
    assertThat(lookup.lookup(new MessageLookup.UnknownMessage(1, key, null, at)))
        .isInstanceOf(ProviderStatus.NotFound.class);
    status.set(500);
    assertThat(lookup.lookup(new MessageLookup.UnknownMessage(1, key, "M4V1", at)))
        .isInstanceOf(ProviderStatus.NotFound.class);
  }

  @Test
  @DisplayName("요청 키가 첫 쪽에 없으면 nextKey로 다음 쪽을 보고, 마지막 쪽까지 없으면 찾지 못함")
  void lookupFollowsNextKey() {
    SolapiMessageLookup lookup = new SolapiMessageLookup(client);
    byte[] key = {(byte) 0xab, 0x12};
    Instant at = Instant.parse("2026-11-08T12:31:00Z");
    pages.put(
        "",
        "{\"nextKey\":\"K2\",\"messageList\":{\"M1\":{\"messageId\":\"M1\",\"statusCode\":\"4000\","
            + "\"customFields\":{\"requestKey\":\"ffff\"}}}}");
    pages.put(
        "K2",
        "{\"startKey\":\"K2\",\"nextKey\":null,\"messageList\":{\"M2\":{\"messageId\":\"M2\","
            + "\"statusCode\":\"3000\",\"customFields\":{\"requestKey\":\"ab12\"}}}}");

    assertThat(lookup.lookup(new MessageLookup.UnknownMessage(1, key, null, at)))
        .isEqualTo(new ProviderStatus.Accepted("M2"));
    assertThat(paths).hasSize(2);
    assertThat(paths.get(1)).contains("&limit=500&startKey=K2");

    pages.put("K2", "{\"startKey\":\"K2\",\"messageList\":{}}");
    paths.clear();
    assertThat(lookup.lookup(new MessageLookup.UnknownMessage(1, key, null, at)))
        .isInstanceOf(ProviderStatus.NotFound.class);
    assertThat(paths).hasSize(2);

    pages.put("K2", "ERROR");
    paths.clear();
    assertThat(lookup.lookup(new MessageLookup.UnknownMessage(1, key, null, at)))
        .isInstanceOf(ProviderStatus.NotFound.class);
    assertThat(paths).hasSize(2);
  }

  @Test
  @DisplayName("다음 쪽이 끝없이 이어지면 상한에서 멈추고 찾지 못함으로 답한다. 같은 nextKey가 되풀이되면 바로 멈춘다")
  void lookupPageLimit(CapturedOutput output) {
    SolapiMessageLookup lookup = new SolapiMessageLookup(client);
    byte[] key = {(byte) 0xab, 0x12};
    Instant at = Instant.parse("2026-11-08T12:31:00Z");
    for (int i = 0; i <= SolapiClient.MAX_LOOKUP_PAGES; i++) {
      pages.put(i == 0 ? "" : "K" + i, "{\"nextKey\":\"K" + (i + 1) + "\",\"messageList\":{}}");
    }

    assertThat(lookup.lookup(new MessageLookup.UnknownMessage(1, key, null, at)))
        .isInstanceOf(ProviderStatus.NotFound.class);
    assertThat(paths).hasSize(SolapiClient.MAX_LOOKUP_PAGES);
    assertThat(output.getAll()).contains("solapi lookup page limit");

    pages.clear();
    paths.clear();
    pages.put("", "{\"nextKey\":\"K1\",\"messageList\":{}}");
    pages.put("K1", "{\"nextKey\":\"K1\",\"messageList\":{}}");
    assertThat(lookup.lookup(new MessageLookup.UnknownMessage(1, key, null, at)))
        .isInstanceOf(ProviderStatus.NotFound.class);
    assertThat(paths).hasSize(2);
  }

  @Test
  @DisplayName("JSON이 아닌 응답은 찾지 못함으로 보고, 로그에 응답 본문 조각(전화번호 등)을 남기지 않는다")
  void malformedLookupResponseIsNotLogged(CapturedOutput output) {
    SolapiMessageLookup lookup = new SolapiMessageLookup(client);
    responseBody.set("{\"messageList\":{\"to\":tel01058219734}}");

    assertThat(
            lookup.lookup(
                new MessageLookup.UnknownMessage(
                    1, new byte[] {1}, "M4V1", Instant.parse("2026-11-08T12:31:00Z"))))
        .isInstanceOf(ProviderStatus.NotFound.class);
    assertThat(output.getAll()).contains("solapi lookup failed").doesNotContain("1058219734");
  }

  @Test
  @DisplayName("헤더 뒤 본문을 아주 천천히 보내도 전체 시간 상한 뒤 알 수 없음으로 끝난다(03 6.4)")
  void slowBodyHitsTotalDeadline() throws Exception {
    HttpServer slow = SlowBodyServer.start(1_000);
    try {
      SolapiClient dripping =
          new SolapiClient(
              new SolapiProperties(
                  "http://127.0.0.1:" + slow.getAddress().getPort(),
                  "KEY",
                  "SECRET",
                  "01000000000",
                  "KA01PF000"),
              Clock.fixed(Instant.parse("2026-11-08T15:30:00Z"), ZoneOffset.UTC));

      SendOutcome outcome =
          assertTimeoutPreemptively(
              Duration.ofSeconds(10),
              () -> dripping.sendAlimtalk("01012345678", "T", Map.of(), "k"));

      assertThat(outcome).isInstanceOf(SendOutcome.Unknown.class);
    } finally {
      slow.stop(0);
    }
  }

  private SendOutcome send() {
    return client.sendAlimtalk("01012345678", "T", Map.of(), "k");
  }
}
