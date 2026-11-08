package com.lottery.lottery_service.notification.solapi;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * SOLAPI 연결 설정({@code lottery.solapi}, {@code config/integrations.yml}). 키 값은 git에 올리지 않는다.
 *
 * @param baseUrl API 주소. 기본 {@code https://api.solapi.com}
 * @param apiKey API 키
 * @param apiSecret API 비밀값. 요청 서명에만 쓰고 로그에 남기지 않는다
 * @param sender 등록한 발신 번호
 * @param channelId 알림톡 비즈니스 채널 ID({@code pfId})
 */
@ConfigurationProperties(prefix = "lottery.solapi")
public record SolapiProperties(
    String baseUrl, String apiKey, String apiSecret, String sender, String channelId) {

  /** 주소가 없으면 운영 주소를 쓴다. */
  public SolapiProperties {
    if (baseUrl == null || baseUrl.isBlank()) {
      baseUrl = "https://api.solapi.com";
    }
    if (baseUrl.endsWith("/")) {
      baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
    }
  }

  @Override
  public String toString() {
    return "SolapiProperties[baseUrl=" + baseUrl + "]";
  }
}
