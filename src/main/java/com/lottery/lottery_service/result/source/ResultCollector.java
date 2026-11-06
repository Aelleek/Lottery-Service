package com.lottery.lottery_service.result.source;

import com.lottery.lottery_service.result.publish.ReceiveOutcome;
import com.lottery.lottery_service.result.publish.ResultPublisher;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * 회차 결과를 한 번 수집한다. 외부 호출은 DB 트랜잭션 밖에서 하고, 검증된 결과만 짧은 게시 트랜잭션으로 넘긴다(03 3절). 미발표와 수집 실패만으로는 운영자 알림을
 * 보내지 않는다(07 1절).
 */
@Component
public class ResultCollector {

  private final DrawSource source;
  private final ResultPublisher publisher;

  /** 수집기를 만든다. */
  public ResultCollector(DrawSource source, ResultPublisher publisher) {
    this.source = source;
    this.publisher = publisher;
  }

  /**
   * 한 번 수집한다.
   *
   * @return 검증된 결과를 받았으면 게시 처리 결과, 미발표나 수집 실패면 빈 값과 함께 {@code parsed}에 이유
   */
  public Attempt collect(int roundNo) {
    ParsedDraw parsed = source.fetch(roundNo);
    if (parsed instanceof ParsedDraw.Valid valid) {
      return new Attempt(parsed, Optional.of(publisher.receive(valid.draw())));
    }
    return new Attempt(parsed, Optional.empty());
  }

  /**
   * 수집 한 번의 결과.
   *
   * @param parsed 원천 응답을 읽은 결과
   * @param outcome 게시 처리 결과. 검증된 결과가 아니면 빈 값
   */
  public record Attempt(ParsedDraw parsed, Optional<ReceiveOutcome> outcome) {}
}
