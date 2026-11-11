package com.lottery.lottery_service.notification.budget;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 월 메시지 예산 설정({@code lottery.message-budget.*}, 02 6.5절). 금액은 0.1원 단위 정수다.
 *
 * @param monthlyLimitUnits 월 예산. 기본 30,000원(300,000)
 * @param resultSharePercent 그중 기본 알림에 쓰는 비율. 기본 90(나머지는 인증 코드용)
 */
@ConfigurationProperties(prefix = "lottery.message-budget")
public record BudgetProperties(Long monthlyLimitUnits, Integer resultSharePercent) {

  /** 설정이 없으면 기본값을 채운다. */
  public BudgetProperties {
    if (monthlyLimitUnits == null) {
      monthlyLimitUnits = 300_000L;
    }
    if (resultSharePercent == null) {
      resultSharePercent = 90;
    }
    if (monthlyLimitUnits < 0 || resultSharePercent < 0 || resultSharePercent > 100) {
      throw new IllegalArgumentException("invalid message budget");
    }
  }

  /** 전체 합계 상한. 기본 알림은 월 예산의 정해진 비율, 인증 코드는 월 예산 전체다. */
  public long capFor(MessagePurpose purpose) {
    return purpose == MessagePurpose.RESULT
        ? monthlyLimitUnits * resultSharePercent / 100
        : monthlyLimitUnits;
  }

  /** 그 용도 메시지만의 합계 상한. 기본 알림은 전체 합계로 보므로 월 예산 전체, 인증 코드는 기본 알림 몫을 뺀 나머지다. */
  public long shareCapFor(MessagePurpose purpose) {
    return purpose == MessagePurpose.RESULT
        ? monthlyLimitUnits
        : monthlyLimitUnits - monthlyLimitUnits * resultSharePercent / 100;
  }
}
