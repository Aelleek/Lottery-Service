package com.lottery.lottery_service.auth;

import com.lottery.lottery_service.account.MemberLogin;
import java.io.Serial;
import java.io.Serializable;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.user.OAuth2User;

/**
 * 로그인 세션에 담는 회원 정보. 제공자가 준 속성(이름, 이메일 등)은 담지 않고 회원·주체 식별자와 세션 회수 버전만 둔다.
 *
 * @param memberId 회원 ID
 * @param memberPublicId 회원 외부 ID
 * @param subjectId 회원 주체 ID
 * @param subjectPublicId 회원 주체 외부 ID
 * @param authVersion 로그인할 때 읽은 {@code member.auth_version}
 * @param provider 로그인한 제공자
 */
public record MemberPrincipal(
    long memberId,
    UUID memberPublicId,
    long subjectId,
    UUID subjectPublicId,
    long authVersion,
    OAuthProvider provider)
    implements OAuth2User, Serializable {

  @Serial private static final long serialVersionUID = 1L;

  /** 로그인 결과로 세션 정보를 만든다. */
  public static MemberPrincipal of(MemberLogin login, OAuthProvider provider) {
    return new MemberPrincipal(
        login.memberId(),
        login.memberPublicId(),
        login.subjectId(),
        login.subjectPublicId(),
        login.authVersion(),
        provider);
  }

  @Override
  public Map<String, Object> getAttributes() {
    return Map.of();
  }

  @Override
  public Collection<? extends GrantedAuthority> getAuthorities() {
    return List.of(new SimpleGrantedAuthority("ROLE_MEMBER"));
  }

  @Override
  public String getName() {
    return memberPublicId.toString();
  }
}
