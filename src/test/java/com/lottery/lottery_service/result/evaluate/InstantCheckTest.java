package com.lottery.lottery_service.result.evaluate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.lottery.lottery_service.common.error.BusinessException;
import com.lottery.lottery_service.common.error.ErrorCode;
import com.lottery.lottery_service.common.number.LottoNumbers;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class InstantCheckTest {

  @Test
  @DisplayName("0세트나 6세트 이상은 DB를 읽지 않고 VALIDATION_FAILED")
  void setCountLimits() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    InstantCheck check = new InstantCheck(jdbc);
    LottoNumbers set = LottoNumbers.of(1, 2, 3, 4, 5, 6);

    for (List<LottoNumbers> sets : List.of(List.<LottoNumbers>of(), Collections.nCopies(6, set))) {
      assertThatThrownBy(() -> check.check(1200, sets))
          .isInstanceOfSatisfying(
              BusinessException.class,
              e -> assertThat(e.getCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }
    verifyNoInteractions(jdbc);
  }
}
