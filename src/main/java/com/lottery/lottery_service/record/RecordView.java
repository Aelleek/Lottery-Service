package com.lottery.lottery_service.record;

import com.lottery.lottery_service.common.number.LottoNumbers;
import java.time.Instant;
import java.util.UUID;

/**
 * 내역 화면의 기록 하나.
 *
 * @param recordId 기록 외부 ID
 * @param roundNo 회차
 * @param numbers 번호
 * @param recommended 추천 표시가 있는지
 * @param purchased 구매 등록됐는지
 * @param version 기록 버전. 취소할 때 함께 보낸다
 * @param expiresAt 보관 기한. 없으면 {@code null}
 * @param evaluation 현재 게시본과 현재 기록 버전의 판정
 */
public record RecordView(
    UUID recordId,
    int roundNo,
    LottoNumbers numbers,
    boolean recommended,
    boolean purchased,
    long version,
    Instant expiresAt,
    RecordEvaluation evaluation) {}
