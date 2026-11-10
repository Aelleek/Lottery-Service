package com.lottery.lottery_service.recommendation;

import com.lottery.lottery_service.common.number.LottoNumbers;
import java.util.HashSet;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 추천 회차보다 앞선 회차의 게시된 1등 조합을 읽는다. 요청마다 읽어 방금 게시한 결과도 바로 반영한다. */
@Component
public class PublishedFirstPrizes {

  private final JdbcTemplate jdbc;

  /** 읽기 도구를 만든다. */
  public PublishedFirstPrizes(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** {@code roundNo}보다 앞선 회차의 게시된 1등 조합. */
  public Set<LottoNumbers> before(int roundNo) {
    Set<LottoNumbers> result = new HashSet<>();
    jdbc.queryForList(
            "SELECT d.numbers_key FROM lotto_round r JOIN draw_result d"
                + " ON d.id = r.published_result_id WHERE r.round_no < ?",
            String.class,
            roundNo)
        .forEach(k -> result.add(LottoNumbers.fromKey(k)));
    return result;
  }
}
