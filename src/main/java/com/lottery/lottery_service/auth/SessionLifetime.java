package com.lottery.lottery_service.auth;

import jakarta.servlet.http.HttpSession;
import java.time.Duration;
import java.time.Instant;

/**
 * 회원 세션의 절대 상한(02 7.2, 2026-11-09 Q5). 로그인한 시각부터 30일이 지나면 쓰지 않은 기간과 상관없이 끝낸다. 로그인 시각이 없는 세션은 세션을 만든
 * 시각으로 본다.
 */
public final class SessionLifetime {

  /** 회원 세션을 쓸 수 있는 최대 기간. */
  public static final Duration MAX = Duration.ofDays(30);

  private static final String STARTED_AT = SessionLifetime.class.getName() + ".startedAt";

  private SessionLifetime() {}

  /** 로그인한 시각을 세션에 적는다. 재인증은 이 시각을 바꾸지 않는다. */
  public static void markStarted(HttpSession session, Instant now) {
    session.setAttribute(STARTED_AT, now);
  }

  /** 세션이 상한을 넘었으면 {@code true}. */
  public static boolean expired(HttpSession session, Instant now) {
    Instant started =
        session.getAttribute(STARTED_AT) instanceof Instant at
            ? at
            : Instant.ofEpochMilli(session.getCreationTime());
    return !now.isBefore(started.plus(MAX));
  }
}
