package com.lottery.lottery_service.auth;

import com.lottery.lottery_service.account.GuestSubject;
import java.time.Instant;
import java.util.UUID;

/**
 * 비회원 자격 쿠키로 확인한 요청 주체. 세션에 저장하지 않고 요청마다 쿠키에서 다시 구한다.
 *
 * @param subjectId 비회원 주체 ID
 * @param subjectPublicId 비회원 주체 외부 ID
 * @param credentialExpiresAt 지금 자격의 만료 시각. 자격을 회전하면 바뀐다
 */
public record GuestPrincipal(long subjectId, UUID subjectPublicId, Instant credentialExpiresAt) {

  /** 자격 확인 결과로 만든다. */
  public static GuestPrincipal of(GuestSubject subject) {
    return new GuestPrincipal(
        subject.subjectId(), subject.subjectPublicId(), subject.credentialExpiresAt());
  }
}
