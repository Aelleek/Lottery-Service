package com.lottery.lottery_service.account;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 비회원 자격 쿠키 설정({@code lottery.guest-cookie}). 운영 기본값은 {@code __Host-lottery_guest}, Secure다. local
 * 프로필만 http용으로 접두어 없는 이름과 {@code secure=false}를 쓴다(03 6.2).
 *
 * @param name 쿠키 이름
 * @param secure Secure 속성
 */
@ConfigurationProperties(prefix = "lottery.guest-cookie")
public record GuestCookieProperties(String name, Boolean secure) {

  /** 빠진 값을 운영 기본값으로 채운다. */
  public GuestCookieProperties {
    if (name == null || name.isBlank()) {
      name = "__Host-lottery_guest";
    }
    if (secure == null) {
      secure = Boolean.TRUE;
    }
  }

  /** 운영 기본값. */
  public static GuestCookieProperties defaults() {
    return new GuestCookieProperties(null, null);
  }
}
