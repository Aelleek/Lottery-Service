package com.lottery.lottery_service.auth.extractor;


import com.lottery.lottery_service.auth.OAuth2UserInfoExtractor;
import com.lottery.lottery_service.auth.OAuthUserInfo;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Kakao OAuth2User attributes → OAuthUserInfo 변환기.
 *
 * - supportsRegistrationId(): 이 추출기가 처리하는 provider id ("kakao")
 * - extract(): Kakao 응답의 중첩 구조를 안전하게 파싱하여 표준 DTO로 변환
 *
 * Kakao 사용자 정보 구조(요지):
 * {
 *   "id": 1234567890,
 *   "kakao_account": {
 *     "email": "...",                // 동의 시 제공
 *     "profile": {
 *       "nickname": "...",
 *       "profile_image_url": "..."
 *     }
 *   }
 * }
 */
@Component
public class KakaoUserInfoExtractor implements OAuth2UserInfoExtractor {

    /** 이 추출기가 처리하는 registrationId (application.yml의 키) */
    @Override
    public String supportsRegistrationId() {
        return "kakao";
    }

    /**
     * Kakao attributes에서 표준화된 사용자 정보를 추출한다.
     * - 누락/비동의 필드는 null 허용
     * - Java 17의 instanceof 패턴 매칭으로 안전 캐스팅
     */
    @Override
    @SuppressWarnings("unchecked")
    public OAuthUserInfo extract(OAuth2User user) {
        Map<String, Object> attrs = user.getAttributes();

        // 필수로 기대하는 식별자
        String id = String.valueOf(attrs.get("id"));

        String email = null;
        String name = null;       // Kakao는 보통 nickname 중심, name은 없을 수 있음
        String nickname = null;
        String profileImage = null;

        Object accountObj = attrs.get("kakao_account");
        if (accountObj instanceof Map<?, ?> account) {
            Object emailObj = account.get("email");
            if (emailObj instanceof String e) email = e;

            Object profileObj = account.get("profile");
            if (profileObj instanceof Map<?, ?> profile) {
                Object nickObj = profile.get("nickname");
                if (nickObj instanceof String n) nickname = n;

                Object nameObj = profile.get("name"); // 제공 안 될 수 있음
                if (nameObj instanceof String nm) name = nm;

                Object imgObj = profile.get("profile_image_url");
                if (imgObj instanceof String url) profileImage = url;
            }
        }

        return new OAuthUserInfo(id, email, name, nickname, profileImage);
    }
}