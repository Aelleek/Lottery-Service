package com.lottery.lottery_service.notification.verify;

import java.time.Instant;
import java.util.UUID;

/**
 * 새로 만든 인증 요청. {@link #code()}는 인증 메시지로만 보내고 저장하거나 기록하지 않는다.
 *
 * @param challengeId 인증 요청 ID
 * @param publicId 인증 요청 외부 ID
 * @param code 6자리 코드
 * @param expiresAt 만료 시각
 */
public record IssuedChallenge(long challengeId, UUID publicId, String code, Instant expiresAt) {

  @Override
  public String toString() {
    return "IssuedChallenge[publicId=" + publicId + ", expiresAt=" + expiresAt + "]";
  }
}
