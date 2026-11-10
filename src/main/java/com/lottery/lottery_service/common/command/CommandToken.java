package com.lottery.lottery_service.common.command;

import java.time.Instant;
import java.util.UUID;

/**
 * 서명을 확인한 명령 토큰의 내용(06 1.1절). 민감한 원문은 넣지 않는다.
 *
 * @param tokenId 토큰 ID
 * @param subjectPublicId 주체 외부 ID
 * @param operation 명령 종류
 * @param issuedAt 발급 시각
 * @param expiresAt 만료 시각
 * @param roundNo 추천 명령의 회차. 다른 명령이면 {@code null}
 * @param scheduleVersion 추천 명령의 일정 버전. 다른 명령이면 {@code null}
 */
public record CommandToken(
    UUID tokenId,
    UUID subjectPublicId,
    String operation,
    Instant issuedAt,
    Instant expiresAt,
    Integer roundNo,
    Long scheduleVersion) {

  /** 주어진 시각에 만료됐는지. 만료 시각부터 만료다. */
  public boolean expiredAt(Instant now) {
    return !now.isBefore(expiresAt);
  }
}
