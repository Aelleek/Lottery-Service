package com.lottery.lottery_service.auth.controller;

import lombok.Builder;
import lombok.Getter;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 인증/인가 관련 공개 API 컨트롤러.
 *
 * <p>프론트의 <code>index.html</code>에서 초기 상태를 판별하기 위해
 * <b>GET /api/me</b>를 호출하여 로그인 여부와 간단한 프로필을 내려준다.</p>
 *
 * <h3>권한</h3>
 * <ul>
 *   <li><b>GET /api/me</b> : <i>permitAll</i> (비로그인도 호출 가능)</li>
 * </ul>
 *
 * <h3>반환 예시</h3>
 * <pre>
 *  { "authenticated": true,
 *    "username": "1234567890",
 *    "name": "홍길동",
 *    "email": "user@example.com",
 *    "provider": "kakao" }
 * </pre>
 */
@RestController
@RequestMapping("/api")
public class AuthController {

    /**
     * OAuth2User의 중첩 속성에서 첫 번째로 발견되는 값을 문자열로 반환한다.
     * <p>예) "kakao_account.profile.nickname" 처럼 점(.)으로 중첩 경로를 표기</p>
     *
     * @param u    OAuth2User
     * @param keys 우선순위 순으로 시도할 키 목록
     * @return 발견값 또는 null
     */
    // 중첩 속성 안전 접근 (a.b.c 지원)
    @SuppressWarnings("unchecked")
    private static String pick(OAuth2User u, String... keys) {
        Map<String, Object> root = u.getAttributes();
        for (String key : keys) {
            Map<String, Object> cur = root;
            Object val = null;
            String[] parts = key.split("\\.");
            for (int i = 0; i < parts.length; i++) {
                Object next = cur.get(parts[i]);
                if (next == null) { val = null; break; }
                if (i == parts.length - 1) { val = next; }
                else if (next instanceof Map) { cur = (Map<String, Object>) next; }
                else { val = null; break; }
            }
            if (val != null) return String.valueOf(val);
        }
        return null;
    }

    /**
     * 인증 상태와 간단한 프로필을 반환합니다.
     *
     * <p>프론트는 이 엔드포인트로 로그인 여부를 확인하고,
     * 로그인된 경우 principal의 attributes에 심어진 {@code memberId}, {@code nickname}, {@code email} 등을 표시할 수 있습니다.
     *
     * <p>주의: {@link CustomOAuth2UserService}가 {@link org.springframework.security.core.user.DefaultOAuth2User}
     * 에 {@code memberId} 등을 attributes로 담아주어야 합니다.
     */
    @GetMapping("/me")
    public ResponseEntity<MeResponse> me(@AuthenticationPrincipal OAuth2User user) {
        if (user == null) {
            return ResponseEntity.ok(MeResponse.builder().authenticated(false).build());
        }
        Map<String, Object> attrs = user.getAttributes();
        Long memberId = parseLong(attrs.get("memberId"));
        return ResponseEntity.ok(MeResponse.builder()
                .authenticated(true)
                .memberId(memberId)
                .name(asString(attrs.get("name")))
                .nickname(asString(attrs.get("nickname")))
                .email(asString(attrs.get("email")))
                .attributes(attrs)
                .build());
    }

    /** 현재 로그인 상태 응답 DTO. */
    @Getter
    @Builder
    static class MeResponse {
        private final boolean authenticated;
        private final Long memberId;
        private final String name;
        private final String nickname;
        private final String email;
        private final Map<String, Object> attributes;
    }

    private static Long parseLong(Object v) {
        if (v instanceof Number n) return n.longValue();
        if (v instanceof String s) try { return Long.parseLong(s); } catch (Exception ignored) {}
        return null;
    }
    private static String asString(Object v) { return v == null ? null : String.valueOf(v); }

}



//    /**
//     * 현재 인증 상태와 프로필 요약을 반환한다.
//     *
//     * @param authentication Spring Security가 주입하는 인증 객체
//     * @return <code>authenticated=false</code>이면 최소 정보만, true면 사용자 요약 포함
//     */
//    @GetMapping("/me")
//    public Map<String, Object> me(Authentication authentication) {
//        // 1) 인증 여부 판단 (Anonymous 토큰은 비로그인으로 간주)
//        boolean authed = authentication != null
//                && authentication.isAuthenticated()
//                && !(authentication instanceof AnonymousAuthenticationToken);
//
//        if (!authed) {
//            return Map.of("authenticated", false);
//        }
//
//        // 2) OAuth2 프로바이더 식별 (kakao/google/naver 등)
//        String provider = (authentication instanceof OAuth2AuthenticationToken t)
//                ? t.getAuthorizedClientRegistrationId() : null;
//
//        // 2) OAuth2 프로바이더 식별 (kakao/google/naver 등)
//        String username = authentication.getName();
//        String name = null;
//        String email = null;
//
//        Object principal = authentication.getPrincipal();
//        if (principal instanceof OAuth2User u) {
//            name = pick(u,
//                    "name", "nickname",
//                    "kakao_account.profile.nickname",
//                    "properties.nickname",
//                    "response.name"   // naver
//            );
//            email = pick(u, "email", "kakao_account.email", "response.email");
//        }
//
//        // 4) 응답 조립 (Map.of는 null 허용 X → LinkedHashMap 사용)
//        var resp = new java.util.LinkedHashMap<String, Object>();
//        resp.put("authenticated", true);
//        resp.put("username", username);
//        resp.put("name", (name != null ? name : username));
//        if (email != null) resp.put("email", email);        // null이면 생략
//        if (provider != null) resp.put("provider", provider);// null이면 생략
//        return resp;
//    }
