package com.lottery.lottery_service.common.web;

import com.lottery.lottery_service.auth.GuestPrincipal;
import com.lottery.lottery_service.auth.MemberPrincipal;
import com.lottery.lottery_service.common.error.BusinessException;
import com.lottery.lottery_service.common.error.ErrorCode;
import java.util.UUID;
import org.springframework.security.core.Authentication;

/**
 * 요청 주체. 회원 세션이나 비회원 쿠키에서만 구한다(03 6.1). 본문의 ID는 쓰지 않는다.
 *
 * @param subjectId 주체 ID
 * @param subjectPublicId 주체 외부 ID
 * @param member 회원이면 {@code true}
 */
public record RequestSubject(long subjectId, UUID subjectPublicId, boolean member) {

  /**
   * 회원이나 비회원 주체.
   *
   * @throws BusinessException 주체가 없으면 {@code UNAUTHENTICATED}
   */
  public static RequestSubject any(Authentication auth) {
    Object principal = auth == null ? null : auth.getPrincipal();
    if (principal instanceof MemberPrincipal m) {
      return new RequestSubject(m.subjectId(), m.subjectPublicId(), true);
    }
    if (principal instanceof GuestPrincipal g) {
      return new RequestSubject(g.subjectId(), g.subjectPublicId(), false);
    }
    throw new BusinessException(ErrorCode.UNAUTHENTICATED, "로그인이 필요합니다.");
  }

  /**
   * 회원 주체. {@code /me} 아래 API가 쓴다.
   *
   * @throws BusinessException 회원 세션이 없으면 {@code UNAUTHENTICATED}
   */
  public static RequestSubject member(Authentication auth) {
    RequestSubject subject = any(auth);
    if (!subject.member()) {
      throw new BusinessException(ErrorCode.UNAUTHENTICATED, "로그인이 필요합니다.");
    }
    return subject;
  }
}
