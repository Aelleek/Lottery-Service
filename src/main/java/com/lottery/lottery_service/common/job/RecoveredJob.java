package com.lottery.lottery_service.common.job;

/**
 * lease 만료로 회수한 job.
 *
 * @param id job ID
 * @param type job 종류
 * @param state 회수 뒤 상태. {@link JobState#RETRY_WAIT} 또는 {@link JobState#DEAD}
 * @param attemptCount 회수 뒤 시도 횟수
 */
public record RecoveredJob(long id, String type, JobState state, int attemptCount) {}
