package com.lottery.lottery_service.admin.web;

import com.lottery.lottery_service.admin.AdminAccess;
import com.lottery.lottery_service.admin.AdminRole;
import com.lottery.lottery_service.admin.AdminSession;
import com.lottery.lottery_service.admin.mfa.AdminMfaStore;
import com.lottery.lottery_service.auth.CsrfTokens;
import com.lottery.lottery_service.auth.MemberPrincipal;
import com.lottery.lottery_service.common.error.BusinessException;
import com.lottery.lottery_service.common.error.ErrorCode;
import com.lottery.lottery_service.common.error.ErrorDetails;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관리자 인증 시작({@code POST /admin/session}, 2026-11-09 Q2). 관리자 권한이 있는 회원이 인증 앱 코드나 복구 코드를 내면 회원 세션 안에
 * 관리자 세션을 연다. 세션 ID를 새로 만들고, 새 세션에 맞는 CSRF 토큰을 준다. 코드를 5번 틀리면 15분 잠긴다({@link AdminMfaStore}).
 */
@RestController
@RequestMapping("/api/v1")
@ConditionalOnProperty(prefix = "lottery.api-v1", name = "enabled", havingValue = "true")
public class AdminSessionController {

  private static final int MAX_CODE_LENGTH = 64;

  private final AdminAccess access;
  private final AdminMfaStore mfa;
  private final CsrfTokens csrf;
  private final Clock clock;

  /** 컨트롤러를 만든다. */
  public AdminSessionController(
      AdminAccess access, AdminMfaStore mfa, CsrfTokens csrf, Clock clock) {
    this.access = access;
    this.mfa = mfa;
    this.csrf = csrf;
    this.clock = clock;
  }

  /** 다중 요소 인증으로 관리자 세션을 연다. */
  @PostMapping("/admin/session")
  public ResponseEntity<Map<String, Object>> start(
      Authentication auth, HttpServletRequest request, @RequestBody Input input) {
    if (auth == null || !(auth.getPrincipal() instanceof MemberPrincipal member)) {
      throw new BusinessException(ErrorCode.UNAUTHENTICATED, "로그인이 필요합니다.");
    }
    access.require(member.memberId(), AdminRole.OPERATOR);
    boolean totp = input != null && input.code() != null;
    boolean recovery = input != null && input.recoveryCode() != null;
    if (totp == recovery
        || (totp && !input.code().matches("[0-9]{6}"))
        || (recovery && input.recoveryCode().length() > MAX_CODE_LENGTH)) {
      throw new BusinessException(ErrorCode.VALIDATION_FAILED, "입력을 확인해 주세요.");
    }
    AdminMfaStore.Outcome outcome =
        totp
            ? mfa.verify(member.memberId(), input.code())
            : mfa.verifyRecovery(member.memberId(), input.recoveryCode());
    switch (outcome) {
      case OK -> {
        request.getSession();
        String sessionId = request.changeSessionId();
        Instant now = clock.instant();
        AdminSession.start(request.getSession(), now);
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .body(
                Map.of(
                    "csrfToken", csrf.issue(member, sessionId),
                    "expiresAt", now.plus(AdminSession.MAX_AGE),
                    "idleTimeoutSeconds", AdminSession.IDLE.toSeconds(),
                    "serverTime", now));
      }
      case LOCKED -> throw mfa.lockedUntil(member.memberId())
          .map(
              until ->
                  new BusinessException(
                      ErrorCode.ATTEMPTS_EXCEEDED, "잠시 뒤 다시 시도해 주세요.", ErrorDetails.retryAt(until)))
          .orElseGet(() -> new BusinessException(ErrorCode.ATTEMPTS_EXCEEDED, "잠시 뒤 다시 시도해 주세요."));
      case INVALID -> throw new BusinessException(ErrorCode.VALIDATION_FAILED, "인증 코드를 확인해 주세요.");
      default -> throw new BusinessException(ErrorCode.FORBIDDEN, "허용되지 않은 요청입니다.");
    }
  }

  /**
   * 요청 본문. 둘 중 하나만 낸다.
   *
   * @param code 인증 앱의 6자리 코드
   * @param recoveryCode 복구 코드
   */
  public record Input(String code, String recoveryCode) {

    @Override
    public String toString() {
      return "Input[redacted]";
    }
  }
}
