package com.lottery.lottery_service.record;

import com.lottery.lottery_service.common.number.LottoNumbers;
import java.util.UUID;

/**
 * 구매 등록 항목 하나의 결과.
 *
 * @param numbers 번호
 * @param recordPublicId 기록 외부 ID
 * @param outcome 결과
 */
public record PurchaseItem(LottoNumbers numbers, UUID recordPublicId, PurchaseOutcome outcome) {}
