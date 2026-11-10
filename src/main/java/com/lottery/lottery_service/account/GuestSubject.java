package com.lottery.lottery_service.account;

import java.time.Instant;
import java.util.UUID;

/**
 * 유효한 비회원 자격이 가리키는 주체.
 *
 * @param subjectId 비회원 주체 ID
 * @param subjectPublicId 비회원 주체 외부 ID
 * @param credentialExpiresAt 지금 자격의 만료 시각
 */
public record GuestSubject(long subjectId, UUID subjectPublicId, Instant credentialExpiresAt) {}
