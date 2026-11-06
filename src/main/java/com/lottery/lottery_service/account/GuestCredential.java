package com.lottery.lottery_service.account;

import java.time.Instant;
import java.util.UUID;

/**
 * 새로 발급한 비회원 자격. {@link #token()} 원문은 쿠키로만 내려주고 저장하거나 기록하지 않는다.
 *
 * @param token 쿠키에 넣을 자격 원문
 * @param subjectId 비회원 주체 ID
 * @param subjectPublicId 비회원 주체 외부 ID
 * @param expiresAt 자격 만료 시각
 */
public record GuestCredential(
    String token, long subjectId, UUID subjectPublicId, Instant expiresAt) {

  @Override
  public String toString() {
    return "GuestCredential[subjectId=" + subjectId + ", expiresAt=" + expiresAt + "]";
  }
}
