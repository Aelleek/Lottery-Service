package com.lottery.lottery_service.result.source;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 동행복권 결과 API({@code common.do?method=getLottoNumber&drwNo=})를 시간 제한을 두고 부른다. 응답은 크기 상한보다 1바이트 더까지만
 * 읽어 큰 본문을 메모리에 다 올리지 않는다. 주소 이동은 따르지 않는다. 주소는 설정으로 바꿀 수 있다.
 */
@Slf4j
@Component
public class DhlotteryDrawSource implements DrawSource {

  private static final Duration TIMEOUT = Duration.ofSeconds(5);

  private final HttpClient http =
      HttpClient.newBuilder()
          .connectTimeout(TIMEOUT)
          .followRedirects(HttpClient.Redirect.NEVER)
          .build();
  private final String baseUrl;

  /** 주소로 만든다. */
  public DhlotteryDrawSource(
      @Value(
              "${lottery.result-api.url:https://www.dhlottery.co.kr/common.do?method=getLottoNumber&drwNo=}")
          String baseUrl) {
    this.baseUrl = baseUrl;
  }

  @Override
  public ParsedDraw fetch(int roundNo) {
    try {
      HttpRequest request =
          HttpRequest.newBuilder(URI.create(baseUrl + roundNo)).timeout(TIMEOUT).GET().build();
      HttpResponse<InputStream> response =
          http.send(request, HttpResponse.BodyHandlers.ofInputStream());
      try (InputStream in = response.body()) {
        if (response.statusCode() != 200) {
          return new ParsedDraw.Invalid("HTTP_" + response.statusCode());
        }
        byte[] body = in.readNBytes(DrawResponseParser.MAX_BYTES + 1);
        if (body.length > DrawResponseParser.MAX_BYTES) {
          return new ParsedDraw.Invalid("SIZE");
        }
        return DrawResponseParser.parse(roundNo, new String(body, StandardCharsets.UTF_8));
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return new ParsedDraw.Invalid("INTERRUPTED");
    } catch (Exception e) {
      log.warn("result api failed round={} error={}", roundNo, e.getClass().getSimpleName());
      return new ParsedDraw.Invalid("NETWORK");
    }
  }
}
