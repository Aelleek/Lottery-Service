package com.lottery.lottery_service.admin.web;

import com.lottery.lottery_service.admin.AdminAccess;
import com.lottery.lottery_service.admin.AdminSession;
import com.lottery.lottery_service.auth.MemberPrincipal;
import com.lottery.lottery_service.common.error.BusinessException;
import com.lottery.lottery_service.common.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 관리자 API({@code /api/v1/admin/**}, 관리자 인증 시작 제외) 앞에서 회원 세션, 현재 관리자 권한, 관리자 세션({@link
 * AdminSession})을 확인한다. 권한이 없으면 403 {@code FORBIDDEN}, 관리자 인증 전이거나 끝났으면 403 {@code
 * ADMIN_AUTH_REQUIRED}다. 역할별 검사는 각 API가 한다.
 */
public class AdminSessionInterceptor implements HandlerInterceptor {

  private final AdminAccess access;
  private final Clock clock;

  /** 확인기를 만든다. */
  public AdminSessionInterceptor(AdminAccess access, Clock clock) {
    this.access = access;
    this.clock = clock;
  }

  @Override
  public boolean preHandle(
      HttpServletRequest request, HttpServletResponse response, Object handler) {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth == null || !(auth.getPrincipal() instanceof MemberPrincipal member)) {
      throw new BusinessException(ErrorCode.UNAUTHENTICATED, "로그인이 필요합니다.");
    }
    if (access.roleOf(member.memberId()).isEmpty()) {
      throw new BusinessException(ErrorCode.FORBIDDEN, "허용되지 않은 요청입니다.");
    }
    if (!AdminSession.touch(request.getSession(false), clock.instant())) {
      throw new BusinessException(ErrorCode.ADMIN_AUTH_REQUIRED, "관리자 인증이 필요합니다.");
    }
    return true;
  }
}
