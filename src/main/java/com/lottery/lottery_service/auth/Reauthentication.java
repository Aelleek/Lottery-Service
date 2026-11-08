package com.lottery.lottery_service.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.time.Instant;
import java.util.Optional;

/**
 * 재인증 상태를 서버 세션에 둔다(C-31, 03 6.3). 재인증을 시작하면 세션의 회원 ID를 기다리는 값으로 적고, 같은 소셜 계정으로 돌아오면 재인증 시각을 적는다.
 * 시각은 화면에 넘기지 않는다. 민감한 API는 {@link #lastAt}으로 읽어 5분 안인지 본다.
 */
public final class Reauthentication {

  /** 재인증을 요청하는 로그인 시작 파라미터. 예: {@code /oauth2/authorization/kakao?reauth=true}. */
  public static final String PARAMETER = "reauth";

  static final String PENDING = Reauthentication.class.getName() + ".pendingMemberId";
  static final String COMPLETED_AT = Reauthentication.class.getName() + ".completedAt";

  private Reauthentication() {}

  /** 그 세션의 마지막 재인증 시각. 없으면 빈 값. */
  public static Optional<Instant> lastAt(HttpServletRequest request) {
    HttpSession session = request.getSession(false);
    if (session == null) {
      return Optional.empty();
    }
    return Optional.ofNullable((Instant) session.getAttribute(COMPLETED_AT));
  }

  static void start(HttpSession session, long memberId) {
    session.setAttribute(PENDING, memberId);
  }

  static Optional<Long> pending(HttpSession session) {
    return session == null
        ? Optional.empty()
        : Optional.ofNullable((Long) session.getAttribute(PENDING));
  }

  static void clear(HttpSession session) {
    if (session != null) {
      session.removeAttribute(PENDING);
    }
  }

  static void complete(HttpSession session, Instant at) {
    session.removeAttribute(PENDING);
    session.setAttribute(COMPLETED_AT, at);
  }
}
