package com.lottery.lottery_service.account;

import java.util.UUID;

/**
 * 로그인에 성공한 회원과 그 주체.
 *
 * @param memberId 회원 ID
 * @param memberPublicId 회원 외부 ID
 * @param subjectId 회원 주체 ID. 기록 소유권과 잠금 기준이다
 * @param subjectPublicId 회원 주체 외부 ID
 * @param authVersion 세션 회수 기준 버전
 * @param newMember 이번 로그인으로 새로 가입했으면 {@code true}
 */
public record MemberLogin(
    long memberId,
    UUID memberPublicId,
    long subjectId,
    UUID subjectPublicId,
    long authVersion,
    boolean newMember) {}
