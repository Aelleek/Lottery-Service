package com.lottery.lottery_service.result.source;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CollectionScheduleTest {

  private static final Instant DRAW = Instant.parse("2026-11-14T11:35:00Z");

  @Test
  @DisplayName("첫 시도는 추첨 10분 뒤, 그 뒤 5분 간격")
  void shortInterval() {
    assertThat(CollectionSchedule.nextAttempt(DRAW, null))
        .isEqualTo(Instant.parse("2026-11-14T11:45:00Z"));
    assertThat(CollectionSchedule.nextAttempt(DRAW, Instant.parse("2026-11-14T11:45:00Z")))
        .isEqualTo(Instant.parse("2026-11-14T11:50:00Z"));
  }

  @Test
  @DisplayName("추첨 다음 날 서울 12:00(UTC 03:00)부터는 1시간 간격이고, 경계를 넘는 시도는 12:00에 맞춘다")
  void longIntervalAfterNextNoon() {
    assertThat(CollectionSchedule.nextAttempt(DRAW, Instant.parse("2026-11-15T02:58:00Z")))
        .isEqualTo(Instant.parse("2026-11-15T03:00:00Z"));
    assertThat(CollectionSchedule.nextAttempt(DRAW, Instant.parse("2026-11-15T03:00:00Z")))
        .isEqualTo(Instant.parse("2026-11-15T04:00:00Z"));
  }
}
