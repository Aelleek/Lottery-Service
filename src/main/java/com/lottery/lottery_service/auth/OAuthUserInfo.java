package com.lottery.lottery_service.auth;

/**
 * 제공자 사용자 정보에서 로그인에 쓰는 값. 계정은 (제공자, 제공자 사용자 ID)로만 잇고(D-007) 이메일·이름·사진은 읽지 않는다.
 *
 * @param providerUserId Kakao {@code id}, Google {@code sub}, Naver {@code response.id}. 없으면 {@code
 *     null}
 */
public record OAuthUserInfo(String providerUserId) {}
