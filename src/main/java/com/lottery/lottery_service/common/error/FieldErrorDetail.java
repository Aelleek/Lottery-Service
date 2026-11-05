package com.lottery.lottery_service.common.error;

/** 입력 오류 위치와 사유. 사용자가 보낸 값은 담지 않는다. */
public record FieldErrorDetail(String path, String reason) {}
