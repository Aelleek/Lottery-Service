package com.lottery.lottery_service.auth;

import com.lottery.lottery_service.member.entity.Member;
import com.lottery.lottery_service.member.oauth.entity.MemberOAuthAccount;
import com.lottery.lottery_service.member.oauth.repository.MemberOAuthAccountRepository;
import com.lottery.lottery_service.member.repository.MemberRepository;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * OAuth2 로그인 성공 시 사용자 정보를 로드하고, (provider, providerUserId) 기준으로 Member/Link를 업서트한다.
 *
 * <p>- 엔티티 직접 new/setter 대신 도메인 팩토리 & 애그리게잇 메소드 사용 (Member.newMember / Member.addOAuthLink /
 * MemberOAuthAccount.newLink)
 */
@Service
@RequiredArgsConstructor
@Transactional
public class CustomOAuth2UserService extends DefaultOAuth2UserService {

  private final MemberRepository memberRepository;
  private final MemberOAuthAccountRepository oauthRepository;
  private final List<OAuth2UserInfoExtractor> extractors;

  /**
   * 1) registrationId로 추출기 선택 → OAuthUserInfo 추출 2) (provider, providerUserId)로 기존 링크 조회 → 없으면
   * 생성/연결 3) lastLoginAt 갱신 및 Security Principal 반환
   */
  @Override
  public OAuth2User loadUser(OAuth2UserRequest userRequest) throws OAuth2AuthenticationException {
    // 1) 공급자 별 사용자 정보 로드
    OAuth2User delegateUser = super.loadUser(userRequest);

    // provider 식별자 (application.yml의 registrationId)
    String registrationId = userRequest.getClientRegistration().getRegistrationId(); // ex) "kakao"

    // 등록된 Extractor 중 지원하는 것 선택
    OAuth2UserInfoExtractor extractor =
        extractors.stream()
            .filter(ext -> ext.supports(registrationId))
            .findFirst()
            .orElseThrow(
                () -> new OAuth2AuthenticationException("Unsupported provider: " + registrationId));

    // 표준 사용자 정보 추출
    OAuthUserInfo info = extractor.extract(delegateUser);
    if (info.providerUserId() == null || info.providerUserId().isBlank()) {
      throw new OAuth2AuthenticationException("Missing provider user id from " + registrationId);
    }

    OAuthProvider provider =
        OAuthProvider.fromRegistrationId(registrationId)
            .orElseThrow(
                () -> new OAuth2AuthenticationException("Unsupported provider: " + registrationId));

    // 기존 링크 조회 → 없으면 생성/연결
    Member member =
        oauthRepository
            .findByProviderAndProviderUserId(provider, info.providerUserId())
            .map(MemberOAuthAccount::getMember)
            .orElseGet(() -> createOrLinkMember(provider, info));

    // 최근 로그인 시각 갱신
    member.setLastLoginAt(Instant.now());

    // Principal 생성 (Kakao의 경우 user-name-attribute = "id")
    String nameAttributeKey =
        userRequest
            .getClientRegistration()
            .getProviderDetails()
            .getUserInfoEndpoint()
            .getUserNameAttributeName();

    Map<String, Object> attributes = new HashMap<>(delegateUser.getAttributes());
    // 프론트(/api/me, @AuthenticationPrincipal)에서 쓰기 위한 내부 식별/표시 정보 추가
    attributes.put("memberId", member.getId());
    attributes.put("name", member.getName());
    attributes.put("nickname", member.getNickname());
    attributes.put("email", member.getEmail());
    // 필요 시 provider 원본 속성 유지됨 (위에서 copy 했기 때문)
    // === CHANGED END ===

    return new DefaultOAuth2User(
        List.of(new SimpleGrantedAuthority("ROLE_USER")),
        attributes, // ← 병합된 attributes
        nameAttributeKey // ← 기존 provider의 name 키 유지(예: "id")
        );
  }

  /**
   * 기존 링크가 없을 때: - 검증된 이메일 보유 기존 Member가 있으면 연결 - 없으면 Member.newMember(...)로 새 회원 생성 후 저장 -
   * Member.addOAuthLink(...)로 링크 생성/양방향 일관성 보장
   */
  private Member createOrLinkMember(OAuthProvider provider, OAuthUserInfo info) {
    // 1) 검증된 이메일로 기존 Member 찾기 (자동 연결은 검증된 이메일만 허용)
    Member target = null;
    if (info.email() != null) {
      target =
          memberRepository.findByEmail(info.email()).filter(Member::isEmailVerified).orElse(null);
    }

    // 2) 없으면 새 Member 생성 (도메인 팩토리)
    if (target == null) {
      target = Member.newMember(info.email(), info.nickname(), info.profileImageUrl());
      target = memberRepository.save(target);
    }

    // 3) 링크 생성 (애그리게잇 메소드) → 레포지토리로 명시 저장
    MemberOAuthAccount link =
        target.addOAuthLink(
            provider, info.providerUserId(), info.email(), info.nickname(), info.profileImageUrl());
    oauthRepository.save(link);

    return target;
  }
}
