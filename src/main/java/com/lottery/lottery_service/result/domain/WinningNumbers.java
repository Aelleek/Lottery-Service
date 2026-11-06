package com.lottery.lottery_service.result.domain;

import com.lottery.lottery_service.common.number.InvalidLottoNumberException;
import com.lottery.lottery_service.common.number.LottoNumber;
import com.lottery.lottery_service.common.number.LottoNumbers;
import java.util.Objects;

/** 한 회차의 당첨 번호 6개와 보너스 번호. 보너스 번호는 당첨 번호와 겹칠 수 없다. */
public record WinningNumbers(LottoNumbers main, LottoNumber bonus) {

  /** 보너스가 당첨 번호와 겹치면 {@link InvalidLottoNumberException}을 던진다. */
  public WinningNumbers {
    Objects.requireNonNull(main, "main");
    Objects.requireNonNull(bonus, "bonus");
    if (main.contains(bonus)) {
      throw new InvalidLottoNumberException("보너스 번호가 당첨 번호와 겹칩니다.");
    }
  }

  /** 기록 번호의 등수를 판정한다. */
  public PrizeRank rankOf(LottoNumbers ticket) {
    Objects.requireNonNull(ticket, "ticket");
    return PrizeRank.of(ticket.matchCount(main), ticket.contains(bonus));
  }
}
