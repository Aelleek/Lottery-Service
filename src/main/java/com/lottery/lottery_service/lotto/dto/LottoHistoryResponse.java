package com.lottery.lottery_service.lotto.dto;

import com.lottery.lottery_service.lotto.entity.LottoHistory;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@AllArgsConstructor
public class LottoHistoryResponse {

    private int round;
    private String numbers;
    private LocalDateTime recommendedAt;
    private boolean isManual;
    private boolean isPurchased;
    private String source;

    /**
     * LottoHistory 엔티티를 응답 DTO로 변환합니다.
     */
    public static LottoHistoryResponse from(LottoHistory history) {
        return new LottoHistoryResponse(
                history.getRound(),
                history.getNumbers(),
                history.getRecommendedAt(),
                history.isManual(),
                history.isPurchased(),
                history.getSource()
        );
    }
}

