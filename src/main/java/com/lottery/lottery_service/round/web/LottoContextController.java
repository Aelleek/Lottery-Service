package com.lottery.lottery_service.round.web;

import com.lottery.lottery_service.round.RoundStore;
import com.lottery.lottery_service.round.domain.RoundContext;
import java.time.Clock;
import java.time.Instant;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/v1/lotto/context}: 현재 회차, 일정 버전, 추천 가능 여부, 다음 개시 시각(06 2절). 일정을 확인할 수 없으면 503
 * {@code SCHEDULE_UNAVAILABLE}이다. 새 API는 {@code lottery.api-v1.enabled=true}일 때만 연다.
 */
@RestController
@ConditionalOnProperty(prefix = "lottery.api-v1", name = "enabled", havingValue = "true")
public class LottoContextController {

  private final RoundStore rounds;
  private final Clock clock;

  /** 컨트롤러를 만든다. */
  public LottoContextController(RoundStore rounds, Clock clock) {
    this.rounds = rounds;
    this.clock = clock;
  }

  /** 현재 회차 상태. */
  @GetMapping("/api/v1/lotto/context")
  public ContextResponse context() {
    Instant now = clock.instant();
    RoundContext context = rounds.calendar().contextAt(now);
    return new ContextResponse(
        context.currentRound(),
        Long.toString(context.scheduleVersion()),
        context.recommendationOpen(),
        context.nextOpenAt(),
        now);
  }

  /**
   * openapi {@code Context}.
   *
   * @param currentRound 이번 회차
   * @param scheduleVersion 일정 버전(십진 문자열)
   * @param recommendationOpen 새 추천을 받는지
   * @param nextOpenAt 마감 구간이면 다음 개시 시각
   * @param serverTime 서버 시각
   */
  public record ContextResponse(
      int currentRound,
      String scheduleVersion,
      boolean recommendationOpen,
      Instant nextOpenAt,
      Instant serverTime) {}
}
