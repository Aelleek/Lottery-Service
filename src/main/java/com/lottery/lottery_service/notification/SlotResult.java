package com.lottery.lottery_service.notification;

/**
 * 슬롯 한 번의 결과(05 8.2절 8번).
 *
 * @param success 실패가 없으면 {@code true}
 * @param cause 실패 원인 코드(07 2.2절). 성공이면 {@code null}
 * @param failed 보내지 못한 메시지 수
 * @param unknown 접수 여부를 모르는 메시지 수
 * @param sent 이번 실행에서 제공자가 접수한 메시지 수
 */
public record SlotResult(boolean success, String cause, int failed, int unknown, int sent) {}
