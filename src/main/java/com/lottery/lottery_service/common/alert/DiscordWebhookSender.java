package com.lottery.lottery_service.common.alert;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Discord 웹훅으로 운영자 알림을 보낸다. 주소는 {@code config/integrations.yml}의 {@code
 * lottery.integrations.discord-webhook-url}이다.
 */
@Component
public class DiscordWebhookSender implements OperatorAlertSender {

  private static final Duration TIMEOUT = Duration.ofSeconds(5);
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
  private final String webhookUrl;

  /** 웹훅 주소로 만든다. 비어 있으면 보낼 때 실패한다. */
  public DiscordWebhookSender(
      @Value("${lottery.integrations.discord-webhook-url:}") String webhookUrl) {
    this.webhookUrl = webhookUrl;
  }

  @Override
  public void send(String content) {
    if (webhookUrl == null || webhookUrl.isBlank()) {
      throw new IllegalStateException("discord webhook is not configured");
    }
    try {
      HttpRequest request =
          HttpRequest.newBuilder(URI.create(webhookUrl))
              .timeout(TIMEOUT)
              .header("Content-Type", "application/json")
              .POST(
                  HttpRequest.BodyPublishers.ofString(
                      MAPPER.writeValueAsString(Map.of("content", content))))
              .build();
      HttpResponse<Void> response = http.send(request, HttpResponse.BodyHandlers.discarding());
      if (response.statusCode() / 100 != 2) {
        throw new IllegalStateException("discord webhook returned " + response.statusCode());
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted", e);
    } catch (IOException e) {
      throw new IllegalStateException("discord webhook failed", e);
    }
  }
}
