package com.lottery.lottery_service.result.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lottery.lottery_service.common.number.LottoNumber;
import com.lottery.lottery_service.common.number.LottoNumbers;
import com.lottery.lottery_service.result.domain.WinningNumbers;
import com.lottery.lottery_service.result.publish.ReceiveOutcome;
import com.lottery.lottery_service.result.publish.ResultPublisher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ResultCollectorTest {

  private final ResultPublisher publisher = mock(ResultPublisher.class);

  @Test
  @DisplayName("검증된 결과만 게시로 넘기고, 미발표와 수집 실패는 게시하지 않고 구분해 돌려준다")
  void onlyValidResultsArePublished() {
    OfficialDraw draw =
        new OfficialDraw(
            1200,
            new WinningNumbers(LottoNumbers.of(1, 2, 3, 4, 5, 6), LottoNumber.of(7)),
            null,
            null,
            new byte[32]);
    when(publisher.receive(draw)).thenReturn(ReceiveOutcome.PUBLISHED);

    assertThat(
            new ResultCollector(r -> new ParsedDraw.Valid(draw), publisher).collect(1200).outcome())
        .contains(ReceiveOutcome.PUBLISHED);
    ResultCollector.Attempt pending =
        new ResultCollector(r -> new ParsedDraw.NotAnnounced(), mock(ResultPublisher.class))
            .collect(1200);
    assertThat(pending.parsed()).isInstanceOf(ParsedDraw.NotAnnounced.class);
    assertThat(pending.outcome()).isEmpty();

    ResultPublisher untouched = mock(ResultPublisher.class);
    new ResultCollector(r -> new ParsedDraw.Invalid("NOT_JSON"), untouched).collect(1200);
    verify(untouched, never()).receive(any());
  }
}
