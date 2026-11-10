package com.lottery.lottery_service.notification;

import java.time.Instant;

/**
 * 결과 알림 발송 슬롯 하나(02 6.2절).
 *
 * @param at 슬롯 시각
 * @param alertOnFailure 이 슬롯이 실패하면 운영자에게 알리는지
 * @param last 자동 발송의 마지막 슬롯인지
 */
public record ResultSlot(Instant at, boolean alertOnFailure, boolean last) {}
