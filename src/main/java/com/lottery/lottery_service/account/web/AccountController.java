package com.lottery.lottery_service.account.web;

import com.lottery.lottery_service.account.withdrawal.WithdrawCommandService;
import com.lottery.lottery_service.auth.MemberPrincipal;
import com.lottery.lottery_service.auth.Reauthentication;
import com.lottery.lottery_service.common.error.BusinessException;
import com.lottery.lottery_service.common.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 탈퇴({@code DELETE /me/account}). 최근 5분 안의 재인증(서버 세션의 재인증 시각)이 있어야 한다(02 7.2, C-31). 접수하면 접근을 바로 막고
 * 이 세션도 지운다.
 */
@RestController
@RequestMapping("/api/v1")
@ConditionalOnProperty(prefix = "lottery.api-v1", name = "enabled", havingValue = "true")
public class AccountController {

  private final WithdrawCommandService withdrawals;
  private final Clock clock;

  /** 컨트롤러를 만든다. */
  public AccountController(WithdrawCommandService withdrawals, Clock clock) {
    this.withdrawals = withdrawals;
    this.clock = clock;
  }

  /** 탈퇴를 접수한다. */
  @DeleteMapping("/me/account")
  public ResponseEntity<Map<String, Object>> withdraw(
      Authentication auth,
      HttpServletRequest request,
      @RequestHeader("X-Command-Token") String commandToken,
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @RequestBody Input input) {
    if (auth == null || !(auth.getPrincipal() instanceof MemberPrincipal member)) {
      throw new BusinessException(ErrorCode.UNAUTHENTICATED, "로그인이 필요합니다.");
    }
    UUID key;
    long version;
    try {
      key = UUID.fromString(idempotencyKey);
      version = Long.parseLong(input.expectedVersion());
    } catch (IllegalArgumentException | NullPointerException e) {
      throw new BusinessException(ErrorCode.VALIDATION_FAILED, "입력 형식을 확인해 주세요.");
    }
    if (commandToken.length() > 4096) {
      throw new BusinessException(ErrorCode.VALIDATION_FAILED, "입력을 확인해 주세요.");
    }
    Instant reauthenticatedAt = Reauthentication.lastAt(request).orElse(null);
    UUID operation =
        withdrawals.withdraw(
            member.memberId(),
            member.subjectId(),
            member.subjectPublicId(),
            commandToken,
            key,
            version,
            reauthenticatedAt);
    HttpSession session = request.getSession(false);
    if (session != null) {
      session.invalidate();
    }
    SecurityContextHolder.clearContext();
    return ResponseEntity.status(HttpStatus.ACCEPTED)
        .cacheControl(CacheControl.noStore())
        .body(
            Map.of(
                "operationId",
                operation,
                "status",
                "PROCESSING",
                "data",
                Map.of("accessBlocked", true, "deletionState", "REQUESTED"),
                "serverTime",
                clock.instant()));
  }

  /**
   * 요청 본문(openapi {@code VersionInput}). 회원 인증 버전이다.
   *
   * @param expectedVersion 화면이 본 버전(십진 문자열)
   */
  public record Input(String expectedVersion) {}
}
