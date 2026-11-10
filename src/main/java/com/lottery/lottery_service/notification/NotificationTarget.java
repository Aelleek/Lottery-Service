package com.lottery.lottery_service.notification;

import com.lottery.lottery_service.result.domain.PrizeRank;
import java.util.List;

/**
 * 한 회원의 회차 기본 알림 대상(02 6.1절). 회원·회차당 한 건으로 묶는다.
 *
 * @param memberId 회원 ID
 * @param records 알릴 기록
 */
public record NotificationTarget(long memberId, List<Item> records) {

  /**
   * 알릴 기록 하나.
   *
   * @param recordId 기록 ID
   * @param rank 등수
   * @param purchased 마감 전에 구매 등록한 번호로서 대상인지. 아니면 추천 번호로서 대상이다
   */
  public record Item(long recordId, PrizeRank rank, boolean purchased) {}
}
