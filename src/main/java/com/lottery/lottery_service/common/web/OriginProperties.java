package com.lottery.lottery_service.common.web;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 쿠키 인증 변경 요청을 받아 줄 화면 출처({@code lottery.web.allowed-origins}). 비우면 요청이 들어온 서버 자신의 출처만 받는다. 예:
 * {@code https://lotto.example.com}.
 *
 * @param allowedOrigins 허용 출처(scheme://host[:port])
 */
@ConfigurationProperties(prefix = "lottery.web")
public record OriginProperties(List<String> allowedOrigins) {

  /** 설정이 없으면 빈 목록. */
  public OriginProperties {
    allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
  }
}
