package com.lottery.lottery_service.lotto.dto.response;

import com.lottery.lottery_service.lotto.entity.LottoRecord;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LottoRecordResponse {

  private int round;
  private String numbers;
  private LocalDateTime recommendedAt;
  private boolean manual;
  private boolean purchased;
  private String source;

  /** LottoRecord 엔티티를 응답 DTO로 변환합니다. */
  public static LottoRecordResponse from(LottoRecord history) {
    return new LottoRecordResponse(
        history.getRound(),
        history.getNumbers(),
        history.getRecommendedAt(),
        history.isManual(),
        history.isPurchased(),
        history.getSource());
  }
}
