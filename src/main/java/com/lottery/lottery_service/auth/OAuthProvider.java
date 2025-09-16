package com.lottery.lottery_service.auth;


import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * 지원하는 소셜 제공자 식별자.
 * - registrationId는 application.yml의 키(kakao/naver/google)와 연결된다.
 */
public enum OAuthProvider {
    KAKAO("kakao"),
    NAVER("naver"),
    GOOGLE("google");

    private final String registrationId;

    OAuthProvider(String registrationId) {
        this.registrationId = registrationId;
    }

    /** e.g., "kakao" → KAKAO (대/소문자 무시) */
    public static Optional<OAuthProvider> fromRegistrationId(String id) {
        if (id == null) return Optional.empty();
        String norm = id.toLowerCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(p -> p.registrationId.equals(norm))
                .findFirst();
    }

    public String registrationId() {
        return registrationId;
    }
}