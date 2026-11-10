package com.lottery.lottery_service.notification.solapi;

import com.lottery.lottery_service.notification.MessageLookup;
import com.lottery.lottery_service.notification.ProviderStatus;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * SOLAPI 메시지 조회로 접수 여부를 모르는 메시지를 찾는다(05 8.3절, C-27). 제공자 메시지 ID가 있으면 그것으로, 없으면 마지막 호출 시각 앞뒤 범위에서
 * {@code customFields}의 요청 키로 맞춘다. 조회가 실패하면 찾지 못한 것으로 답해 미접수로 단정하지 않는다. {@code
 * lottery.solapi.api-key}가 있을 때만 켜진다.
 */
@Component
@Primary
@ConditionalOnProperty(prefix = "lottery.solapi", name = "api-key")
@EnableConfigurationProperties(SolapiProperties.class)
public class SolapiMessageLookup implements MessageLookup {

  /** 마지막 호출 시각 앞쪽 조회 범위. */
  static final Duration BEFORE = Duration.ofMinutes(5);

  /** 마지막 호출 시각 뒤쪽 조회 범위. */
  static final Duration AFTER = Duration.ofMinutes(30);

  private static final Logger log = LoggerFactory.getLogger(SolapiMessageLookup.class);

  private final SolapiClient client;

  /** 설정으로 만든다. */
  public SolapiMessageLookup(SolapiProperties properties, Clock clock) {
    this(new SolapiClient(properties, clock));
  }

  SolapiMessageLookup(SolapiClient client) {
    this.client = client;
  }

  /** 요청 키를 {@code customFields}에 넣을 문자열로 바꾼다(소문자 hex). 발송과 조회가 같은 값을 쓴다. */
  public static String requestKey(byte[] providerRequestKey) {
    return HexFormat.of().formatHex(providerRequestKey);
  }

  @Override
  public ProviderStatus lookup(UnknownMessage message) {
    try {
      if (message.providerMessageId() != null && !message.providerMessageId().isBlank()) {
        return client.findByMessageId(message.providerMessageId());
      }
      Instant at = message.lastAttemptAt();
      if (at == null) {
        return new ProviderStatus.NotFound();
      }
      return client.findByRequestKey(
          requestKey(message.providerRequestKey()), at.minus(BEFORE), at.plus(AFTER));
    } catch (IOException e) {
      log.warn("solapi lookup failed: message={} cause={}", message.id(), e.getMessage());
      return new ProviderStatus.NotFound();
    }
  }
}
