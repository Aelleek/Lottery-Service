package com.lottery.lottery_service.auth.web;

import com.lottery.lottery_service.account.GuestIssueService;
import com.lottery.lottery_service.auth.CsrfTokens;
import com.lottery.lottery_service.auth.GuestAuthenticationFilter;
import com.lottery.lottery_service.auth.GuestPrincipal;
import com.lottery.lottery_service.auth.MemberPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Instant;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 로그인 상태 조회({@code GET /session})와 비회원 자격 발급({@code POST /guest-credentials}). 둘 다 CSRF 토큰을 본문으로
 * 준다(D-051). 응답은 캐시하지 않는다.
 */
@RestController
@RequestMapping("/api/v1")
@ConditionalOnProperty(prefix = "lottery.api-v1", name = "enabled", havingValue = "true")
public class SessionController {

  private final CsrfTokens tokens;
  private final GuestIssueService guests;
  private final Clock clock;

  /** 컨트롤러를 만든다. */
  public SessionController(CsrfTokens tokens, GuestIssueService guests, Clock clock) {
    this.tokens = tokens;
    this.guests = guests;
    this.clock = clock;
  }

  /** 지금 요청의 주체 종류와 그 주체에 묶은 CSRF 토큰. 주체가 없으면 토큰도 없다. */
  @GetMapping("/session")
  public ResponseEntity<SessionState> session(Authentication auth, HttpServletRequest request) {
    Object principal = auth == null ? null : auth.getPrincipal();
    SessionState state;
    if (principal instanceof MemberPrincipal member) {
      String sessionId = request.getSession().getId();
      state =
          new SessionState(
              SessionState.Kind.MEMBER, tokens.issue(member, sessionId), null, serverTime());
    } else if (principal instanceof GuestPrincipal guest) {
      state =
          new SessionState(
              SessionState.Kind.GUEST,
              tokens.issue(guest),
              guest.credentialExpiresAt(),
              serverTime());
    } else {
      state = new SessionState(SessionState.Kind.NONE, null, null, serverTime());
    }
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(state);
  }

  /** 비회원 자격을 발급한다. 유효한 자격이 이미 있으면 같은 주체를 유지하고 토큰만 새로 준다. 회원 세션이면 403이다. */
  @PostMapping("/guest-credentials")
  public ResponseEntity<GuestCredentialCreated> createGuestCredential(
      Authentication auth, HttpServletRequest request) {
    boolean member = auth != null && auth.getPrincipal() instanceof MemberPrincipal;
    String existing = GuestAuthenticationFilter.cookieValue(request, guests.cookieName());
    GuestIssueService.Outcome outcome = guests.issue(existing, member, request.getRemoteAddr());
    ResponseEntity.BodyBuilder response =
        ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore());
    GuestPrincipal guest;
    if (outcome instanceof GuestIssueService.Outcome.Issued issued) {
      response.header(HttpHeaders.SET_COOKIE, issued.cookie().toString());
      guest =
          new GuestPrincipal(
              issued.credential().subjectId(),
              issued.credential().subjectPublicId(),
              issued.credential().expiresAt());
    } else {
      guest = GuestPrincipal.of(((GuestIssueService.Outcome.Existing) outcome).subject());
    }
    return response.body(
        new GuestCredentialCreated(guest.credentialExpiresAt(), tokens.issue(guest)));
  }

  private Instant serverTime() {
    return clock.instant();
  }

  /**
   * 로그인 상태.
   *
   * @param kind 주체 종류
   * @param csrfToken 변경 요청 헤더로 보낼 토큰. 주체가 없으면 {@code null}
   * @param guestExpiresAt 비회원 자격 만료 시각. 비회원이 아니면 {@code null}
   * @param serverTime 서버 시각
   */
  public record SessionState(
      Kind kind, String csrfToken, Instant guestExpiresAt, Instant serverTime) {

    /** 주체 종류. */
    public enum Kind {
      MEMBER,
      GUEST,
      NONE
    }
  }

  /**
   * 비회원 자격 발급 응답. 자격 원문은 쿠키로만 준다.
   *
   * @param expiresAt 자격 만료 시각
   * @param csrfToken 변경 요청 헤더로 보낼 토큰
   */
  public record GuestCredentialCreated(Instant expiresAt, String csrfToken) {}
}
