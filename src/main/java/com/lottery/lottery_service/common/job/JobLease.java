package com.lottery.lottery_service.common.job;

import java.time.Instant;
import java.util.UUID;

/**
 * worker가 가져간 job과 그 처리 권한. 저장할 때마다 {@link #token()}이 아직 유효한지 {@link JobStore}가 확인한다.
 *
 * @param id job ID
 * @param type job 종류
 * @param payload 등록할 때 넣은 JSON
 * @param cursor 마지막으로 저장한 진행 위치 JSON. 없으면 {@code null}
 * @param token lease 토큰
 * @param leaseUntil lease 만료 시각
 * @param attemptCount 이번 처리 전까지 실패하거나 회수된 횟수
 */
public record JobLease(
    long id,
    String type,
    String payload,
    String cursor,
    UUID token,
    Instant leaseUntil,
    int attemptCount) {}
