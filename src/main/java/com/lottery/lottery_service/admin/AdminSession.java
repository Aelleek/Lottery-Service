package com.lottery.lottery_service.admin;

import jakarta.servlet.http.HttpSession;
import java.time.Duration;
import java.time.Instant;

/**
 * 회원 세션 안의 관리자 세션(02 7.2, 2026-11-09 Q2). 다중 요소 인증을 마친 시각과 마지막 관리자 요청 시각을 회원 세션에 두고, 관리자 API는 인증한 지
 * 8시간, 마지막 관리자 요청에서 30분 안일 때만 받는다. 로그인 유지는 없다.
 */
public final class AdminSession {

  /** 관리자 인증 뒤 최대 사용 기간. */
  public static final Duration MAX_AGE = Duration.ofHours(8);

  /** 관리자 요청 없이 지나면 끝나는 기간. */
  public static final Duration IDLE = Duration.ofMinutes(30);

  static final String AUTHENTICATED_AT = AdminSession.class.getName() + ".authenticatedAt";
  static final String LAST_REQUEST_AT = AdminSession.class.getName() + ".lastRequestAt";

  private AdminSession() {}

  /** 다중 요소 인증을 마쳤다. */
  public static void start(HttpSession session, Instant now) {
    session.setAttribute(AUTHENTICATED_AT, now);
    session.setAttribute(LAST_REQUEST_AT, now);
  }

  /**
   * 관리자 요청을 받아도 되는지 보고, 받으면 마지막 요청 시각을 바꾼다. 끝난 관리자 세션은 지운다(회원 세션은 그대로).
   *
   * @return 받아도 되면 {@code true}
   */
  public static boolean touch(HttpSession session, Instant now) {
    if (session == null
        || !(session.getAttribute(AUTHENTICATED_AT) instanceof Instant authenticated)
        || !(session.getAttribute(LAST_REQUEST_AT) instanceof Instant last)) {
      return false;
    }
    if (!now.isBefore(authenticated.plus(MAX_AGE)) || !now.isBefore(last.plus(IDLE))) {
      end(session);
      return false;
    }
    session.setAttribute(LAST_REQUEST_AT, now);
    return true;
  }

  /** 관리자 세션을 끝낸다. */
  public static void end(HttpSession session) {
    session.removeAttribute(AUTHENTICATED_AT);
    session.removeAttribute(LAST_REQUEST_AT);
  }
}
