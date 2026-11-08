package com.lottery.lottery_service.common.command.web;

import com.lottery.lottery_service.auth.GuestPrincipal;
import com.lottery.lottery_service.auth.MemberPrincipal;
import com.lottery.lottery_service.common.command.CommandTokenService;
import com.lottery.lottery_service.common.error.BusinessException;
import com.lottery.lottery_service.common.error.ErrorCode;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 명령 토큰 발급({@code POST /command-tokens}). 주체는 회원 세션이나 비회원 쿠키에서만 구한다. 발급 자체는 업무 명령이 아니다. CSRF 확인은 보안
 * 필터가 먼저 한다.
 */
@RestController
@RequestMapping("/api/v1")
@ConditionalOnProperty(prefix = "lottery.api-v1", name = "enabled", havingValue = "true")
public class CommandTokenController {

  private final CommandTokenService tokens;

  /** 컨트롤러를 만든다. */
  public CommandTokenController(CommandTokenService tokens) {
    this.tokens = tokens;
  }

  /** 명령 토큰을 발급한다. 주체가 없으면 401, 허용되지 않은 명령이면 403이다. */
  @PostMapping("/command-tokens")
  public ResponseEntity<CommandTokenService.IssuedToken> issue(
      Authentication auth, @RequestBody Request request) {
    Object principal = auth == null ? null : auth.getPrincipal();
    UUID subject;
    boolean member;
    if (principal instanceof MemberPrincipal m) {
      subject = m.subjectPublicId();
      member = true;
    } else if (principal instanceof GuestPrincipal g) {
      subject = g.subjectPublicId();
      member = false;
    } else {
      throw new BusinessException(ErrorCode.UNAUTHENTICATED, "로그인이 필요합니다.");
    }
    Long version = null;
    if (request.scheduleVersion() != null) {
      try {
        version = Long.parseLong(request.scheduleVersion());
      } catch (NumberFormatException e) {
        throw new BusinessException(ErrorCode.VALIDATION_FAILED, "일정 버전 형식이 틀렸습니다.");
      }
    }
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(tokens.issue(subject, member, request.operation(), request.round(), version));
  }

  /**
   * 요청 본문(openapi {@code CommandTokenRequest}).
   *
   * @param operation 명령 종류
   * @param round 추천 회차
   * @param scheduleVersion 추천 일정 버전(십진 문자열)
   */
  public record Request(String operation, Integer round, String scheduleVersion) {}
}
