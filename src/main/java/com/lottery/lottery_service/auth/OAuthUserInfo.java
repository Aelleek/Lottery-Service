package com.lottery.lottery_service.auth;

/**
 * 제공자 사용자 정보에서 로그인에 쓰는 값. 계정은 (제공자, 제공자 사용자 ID)로만 잇는다(D-007). 제공자 응답에 이메일·이름·사진이 와도 여기에 담지 않는다.
 *
 * @param providerUserId Kakao {@code id}, Google {@code sub}, Naver {@code response.id}. 없으면 {@code
 *     null}
 */
public record OAuthUserInfo(String providerUserId) {}
