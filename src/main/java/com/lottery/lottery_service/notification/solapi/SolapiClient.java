package com.lottery.lottery_service.notification.solapi;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lottery.lottery_service.notification.ProviderStatus;
import com.lottery.lottery_service.notification.SendOutcome;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Iterator;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SOLAPI 메시지 API 호출. DB 트랜잭션 밖에서 부른다. 연결·응답 시간 제한은 5초, 응답 본문은 256KB까지만 읽고, 주소 이동은 따르지 않는다(03 6.4).
 * 로그에는 수신 번호·본문·키를 남기지 않는다.
 *
 * <p>상태 코드(SOLAPI 메시지 상태 코드 문서): 2000 정상 접수, 3000 이통사 접수(결과 대기), 4000 수신 완료. 1xxx는 접수 중 오류, 그 밖의
 * 2xxx·3xxx는 처리 중 오류다.
 */
public class SolapiClient {

  /** 요청 키를 넣는 {@code customFields} 이름. */
  public static final String REQUEST_KEY_FIELD = "requestKey";

  private static final Logger log = LoggerFactory.getLogger(SolapiClient.class);
  private static final Duration TIMEOUT = Duration.ofSeconds(5);
  private static final int MAX_BODY = 256 * 1024;

  private final SolapiProperties properties;
  private final Clock clock;
  private final HttpClient http;
  private final ObjectMapper json = new ObjectMapper();

  /** 설정과 시계로 만든다. */
  public SolapiClient(SolapiProperties properties, Clock clock) {
    this.properties = properties;
    this.clock = clock;
    this.http =
        HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
  }

  /**
   * 알림톡 한 건을 보낸다({@code POST /messages/v4/send-many/fast}).
   *
   * @param to 국내 형식 수신 번호(숫자만)
   * @param templateId 심사를 마친 알림톡 템플릿 ID
   * @param variables 템플릿 치환 값. 키는 {@code #{이름}}
   * @param requestKey 같은 메시지 재시도에 같은 값을 쓰는 요청 키. {@code customFields}에 넣어 조회로 찾는다
   * @return 접수({@code Accepted}), 확실한 미접수({@code NotAccepted}), 같은 요청으로 다시 해도 안 되는 거절({@code
   *     Rejected}), 응답을 못 받음({@code Unknown})
   */
  public SendOutcome sendAlimtalk(
      String to, String templateId, Map<String, String> variables, String requestKey) {
    ObjectNode message = json.createObjectNode();
    message.put("to", to);
    message.put("from", properties.sender());
    message.put("type", "ATA");
    ObjectNode kakao = message.putObject("kakaoOptions");
    kakao.put("pfId", properties.channelId());
    kakao.put("templateId", templateId);
    kakao.put("disableSms", true);
    ObjectNode vars = kakao.putObject("variables");
    variables.forEach(vars::put);
    message.putObject("customFields").put(REQUEST_KEY_FIELD, requestKey);
    ObjectNode body = json.createObjectNode();
    body.putArray("messages").add(message);
    body.put("allowDuplicates", false);
    body.put("showMessageList", true);

    Response response;
    try {
      response =
          call(
              HttpRequest.newBuilder(uri("/messages/v4/send-many/fast"))
                  .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                  .header("Content-Type", "application/json"));
    } catch (IOException e) {
      log.warn("solapi send failed without response: {}", e.getClass().getSimpleName());
      return new SendOutcome.Unknown();
    }
    if (response.status() >= 500) {
      return new SendOutcome.Unknown();
    }
    if (response.status() >= 400) {
      return new SendOutcome.NotAccepted("HTTP_" + response.status());
    }
    JsonNode accepted = response.body().path("messageList").path(0);
    if ("2000".equals(accepted.path("statusCode").asText())
        && !accepted.path("messageId").asText().isEmpty()) {
      return new SendOutcome.Accepted(accepted.path("messageId").asText());
    }
    JsonNode failed = response.body().path("failedMessageList").path(0);
    String code = failed.path("statusCode").asText("");
    if (code.matches("1\\d{3}")) {
      return new SendOutcome.Rejected("SOLAPI_" + code);
    }
    if (code.matches("\\d{4}")) {
      return new SendOutcome.NotAccepted("SOLAPI_" + code);
    }
    return new SendOutcome.Unknown();
  }

  /** 제공자 메시지 ID로 상태를 조회한다({@code GET /messages/v4/list?messageId=}). */
  public ProviderStatus findByMessageId(String messageId) throws IOException {
    return firstMatch(query("messageId=" + encode(messageId)), null);
  }

  /** 호출 시각 범위에서 {@code customFields}의 요청 키가 같은 메시지를 찾는다. */
  public ProviderStatus findByRequestKey(String requestKey, Instant from, Instant until)
      throws IOException {
    DateTimeFormatter format = DateTimeFormatter.ISO_OFFSET_DATE_TIME;
    return firstMatch(
        query(
            "dateType=CREATED&startDate="
                + encode(format.format(from.atOffset(ZoneOffset.UTC)))
                + "&endDate="
                + encode(format.format(until.atOffset(ZoneOffset.UTC)))
                + "&limit=500"),
        requestKey);
  }

  private JsonNode query(String parameters) throws IOException {
    Response response = call(HttpRequest.newBuilder(uri("/messages/v4/list?" + parameters)).GET());
    if (response.status() != 200) {
      throw new IOException("solapi lookup status " + response.status());
    }
    return response.body().path("messageList");
  }

  private static ProviderStatus firstMatch(JsonNode list, String requestKey) {
    Iterator<JsonNode> messages = list.elements();
    while (messages.hasNext()) {
      JsonNode message = messages.next();
      if (requestKey != null
          && !requestKey.equals(message.path("customFields").path(REQUEST_KEY_FIELD).asText())) {
        continue;
      }
      String id = message.path("messageId").asText();
      String code = message.path("statusCode").asText("");
      return switch (code) {
        case "4000" -> new ProviderStatus.Delivered(id);
        case "2000", "3000" -> new ProviderStatus.Accepted(id);
        default -> code.matches("\\d{4}")
            ? new ProviderStatus.Failed("SOLAPI_" + code)
            : new ProviderStatus.NotFound();
      };
    }
    return new ProviderStatus.NotFound();
  }

  private Response call(HttpRequest.Builder request) throws IOException {
    HttpRequest built =
        request
            .timeout(TIMEOUT)
            .header(
                "Authorization",
                SolapiAuth.header(properties.apiKey(), properties.apiSecret(), clock.instant()))
            .build();
    try {
      HttpResponse<InputStream> response =
          http.send(built, HttpResponse.BodyHandlers.ofInputStream());
      try (InputStream in = response.body()) {
        byte[] bytes = in.readNBytes(MAX_BODY + 1);
        if (bytes.length > MAX_BODY) {
          throw new IOException("solapi response too large");
        }
        JsonNode body = bytes.length == 0 ? json.createObjectNode() : parse(bytes);
        return new Response(response.statusCode(), body == null ? json.createObjectNode() : body);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("interrupted", e);
    }
  }

  private JsonNode parse(byte[] bytes) throws IOException {
    try {
      return json.readTree(bytes);
    } catch (JsonProcessingException e) {
      throw new IOException("solapi response is not json");
    }
  }

  private URI uri(String path) {
    return URI.create(properties.baseUrl() + path);
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private record Response(int status, JsonNode body) {}
}
