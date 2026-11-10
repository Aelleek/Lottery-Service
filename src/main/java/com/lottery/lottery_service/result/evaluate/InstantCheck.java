package com.lottery.lottery_service.result.evaluate;

import com.lottery.lottery_service.common.error.BusinessException;
import com.lottery.lottery_service.common.error.ErrorCode;
import com.lottery.lottery_service.common.number.LottoNumbers;
import com.lottery.lottery_service.result.domain.PrizeRank;
import com.lottery.lottery_service.result.query.RoundResult;
import com.lottery.lottery_service.result.query.RoundResultQuery;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** 즉시 확인(02 5절). 회원·비회원이 회차와 번호 최대 5세트를 넣으면 현재 게시본으로 등수를 알려 준다. 아무것도 저장하지 않는다. */
@Component
public class InstantCheck {

  /** 한 번에 확인할 수 있는 세트 수. */
  public static final int MAX_SETS = 5;

  private final RoundResultQuery results;

  /** 확인기를 만든다. */
  public InstantCheck(RoundResultQuery results) {
    this.results = results;
  }

  /**
   * 등수를 확인한다.
   *
   * @return 회차 결과와 입력 순서대로 등수(게시 전이면 등수는 빈 목록). 등록된 회차가 아니면 빈 값
   * @throws BusinessException 0세트나 5세트 초과면 {@code VALIDATION_FAILED}
   */
  public Optional<Checked> check(int roundNo, List<LottoNumbers> sets) {
    if (sets.isEmpty() || sets.size() > MAX_SETS) {
      throw new BusinessException(ErrorCode.VALIDATION_FAILED, "한 번에 1~5세트까지 확인할 수 있습니다.");
    }
    return results
        .find(roundNo)
        .map(
            result ->
                new Checked(
                    result,
                    result.winning() == null
                        ? List.of()
                        : sets.stream().map(result.winning()::rankOf).toList()));
  }

  /**
   * 즉시 확인 결과.
   *
   * @param result 회차 결과
   * @param ranks 입력 순서대로 등수. 게시 전이면 빈 목록
   */
  public record Checked(RoundResult result, List<PrizeRank> ranks) {}
}
