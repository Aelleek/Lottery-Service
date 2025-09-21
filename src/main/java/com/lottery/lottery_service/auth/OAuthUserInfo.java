package com.lottery.lottery_service.auth;

/** 제공자 무관 공통 사용자 정보 뷰. null 허용 필드는 제공자 동의/정책에 따라 비어 있을 수 있다. */
public record OAuthUserInfo(
    String providerUserId, // Kakao:id / Google:sub / Naver:response.id
    String email, // null 가능
    String name, // 풀네임(있으면)
    String nickname, // 표시명(있으면)
    String profileImageUrl // 프로필 이미지 URL(있으면)
    ) {}
