package com.lottery.lottery_service.result.evaluate;

import com.lottery.lottery_service.common.error.BusinessException;
import com.lottery.lottery_service.common.error.ErrorCode;
import com.lottery.lottery_service.common.number.LottoNumber;
import com.lottery.lottery_service.common.number.LottoNumbers;
import com.lottery.lottery_service.result.domain.PrizeRank;
import com.lottery.lottery_service.result.domain.WinningNumbers;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 즉시 확인(02 5절). 회원·비회원이 회차와 번호 최대 5세트를 넣으면 현재 게시본으로 등수를 알려 준다. 아무것도 저장하지 않는다. */
@Component
public class InstantCheck {

  /** 한 번에 확인할 수 있는 세트 수. */
  public static final int MAX_SETS = 5;

  private final JdbcTemplate jdbc;

  /** 확인기를 만든다. */
  public InstantCheck(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * 등수를 확인한다.
   *
   * @return 입력 순서대로 등수. 그 회차 결과가 아직 게시되지 않았으면 빈 값
   * @throws BusinessException 0세트나 5세트 초과면 {@code VALIDATION_FAILED}
   */
  public Optional<List<PrizeRank>> check(int roundNo, List<LottoNumbers> sets) {
    if (sets.isEmpty() || sets.size() > MAX_SETS) {
      throw new BusinessException(ErrorCode.VALIDATION_FAILED, "한 번에 1~5세트까지 확인할 수 있습니다.");
    }
    List<Map<String, Object>> rows =
        jdbc.queryForList(
            "SELECT d.numbers_key, d.bonus_number FROM lotto_round r JOIN draw_result d"
                + " ON d.id = r.published_result_id WHERE r.round_no = ?",
            roundNo);
    if (rows.isEmpty()) {
      return Optional.empty();
    }
    WinningNumbers winning =
        new WinningNumbers(
            LottoNumbers.fromKey((String) rows.get(0).get("numbers_key")),
            LottoNumber.of(((Number) rows.get(0).get("bonus_number")).intValue()));
    return Optional.of(sets.stream().map(winning::rankOf).toList());
  }
}
