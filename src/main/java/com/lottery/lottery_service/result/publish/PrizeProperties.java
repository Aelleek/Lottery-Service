package com.lottery.lottery_service.result.publish;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 고정 금액 등수의 1게임 금액({@code lottery.prize.*}, D-046). 4등 50,000원, 5등 5,000원이 기본값이다.
 *
 * @param fourth 4등 금액
 * @param fifth 5등 금액
 */
@ConfigurationProperties(prefix = "lottery.prize")
public record PrizeProperties(Long fourth, Long fifth) {

  /** 설정이 없으면 기본값을 채운다. */
  public PrizeProperties {
    if (fourth == null) {
      fourth = 50_000L;
    }
    if (fifth == null) {
      fifth = 5_000L;
    }
  }
}
