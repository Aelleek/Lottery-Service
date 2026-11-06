package com.lottery.lottery_service.result.source;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 동행복권 결과 API({@code common.do?method=getLottoNumber&drwNo=})를 시간 제한을 두고 부른다. 주소는 설정으로 바꿀 수 있다. */
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
      HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200) {
        return new ParsedDraw.Invalid("HTTP_" + response.statusCode());
      }
      return DrawResponseParser.parse(roundNo, response.body());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return new ParsedDraw.Invalid("INTERRUPTED");
    } catch (Exception e) {
      log.warn("result api failed round={} error={}", roundNo, e.getClass().getSimpleName());
      return new ParsedDraw.Invalid("NETWORK");
    }
  }
}
