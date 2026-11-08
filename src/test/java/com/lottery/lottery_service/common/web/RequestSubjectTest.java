package com.lottery.lottery_service.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lottery.lottery_service.auth.GuestPrincipal;
import com.lottery.lottery_service.auth.MemberPrincipal;
import com.lottery.lottery_service.auth.OAuthProvider;
import com.lottery.lottery_service.common.error.BusinessException;
import com.lottery.lottery_service.common.error.ErrorCode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;

class RequestSubjectTest {

  private final MemberPrincipal member =
      new MemberPrincipal(7, UUID.randomUUID(), 11, UUID.randomUUID(), 0, OAuthProvider.KAKAO);
  private final GuestPrincipal guest =
      new GuestPrincipal(3, UUID.randomUUID(), Instant.parse("2026-12-01T00:00:00Z"));

  @Test
  @DisplayName("회원 세션과 비회원 쿠키의 주체만 쓰고, 없거나 익명이면 UNAUTHENTICATED")
  void any() {
    RequestSubject m =
        RequestSubject.any(new OAuth2AuthenticationToken(member, member.getAuthorities(), "kakao"));
    assertThat(m).isEqualTo(new RequestSubject(11, member.subjectPublicId(), true));
    RequestSubject g =
        RequestSubject.any(new PreAuthenticatedAuthenticationToken(guest, null, List.of()));
    assertThat(g).isEqualTo(new RequestSubject(3, guest.subjectPublicId(), false));

    assertUnauthenticated(() -> RequestSubject.any(null));
    assertUnauthenticated(
        () ->
            RequestSubject.any(
                new AnonymousAuthenticationToken(
                    "k", "anonymousUser", List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS")))));
  }

  @Test
  @DisplayName("회원 API는 비회원 주체를 받지 않는다")
  void memberOnly() {
    assertUnauthenticated(
        () ->
            RequestSubject.member(new PreAuthenticatedAuthenticationToken(guest, null, List.of())));
    assertThat(
            RequestSubject.member(
                    new OAuth2AuthenticationToken(member, member.getAuthorities(), "kakao"))
                .member())
        .isTrue();
  }

  private static void assertUnauthenticated(Runnable call) {
    assertThatThrownBy(call::run)
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.getCode()).isEqualTo(ErrorCode.UNAUTHENTICATED));
  }
}
