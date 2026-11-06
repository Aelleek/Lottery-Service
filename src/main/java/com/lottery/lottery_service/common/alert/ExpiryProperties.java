package com.lottery.lottery_service.common.alert;

import java.time.LocalDate;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 만료를 지켜볼 인증서·연동 목록({@code lottery.expirations.items}, 07 2.1절). 이름과 만료일(서울 기준 날짜)이다. 예: {@code
 * solapi-api-key: 2027-03-31}. 비밀값이 아니라 날짜만 둔다.
 *
 * @param items 이름별 만료일. 없으면 빈 목록
 */
@ConfigurationProperties(prefix = "lottery.expirations")
public record ExpiryProperties(Map<String, LocalDate> items) {

  /** 설정이 없으면 빈 목록. */
  public ExpiryProperties {
    items = items == null ? Map.of() : Map.copyOf(items);
  }
}
