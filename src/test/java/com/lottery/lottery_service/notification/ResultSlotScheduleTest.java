package com.lottery.lottery_service.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ResultSlotScheduleTest {

  private static final Instant DRAW = Instant.parse("2026-11-14T11:35:00Z");

  @Test
  @DisplayName("추첨일 21:30~23:30과 다음 날 09:00~12:00의 30분 간격 12개 슬롯이고, 운영자 알림 슬롯은 4개, 마지막은 12:00")
  void slots() {
    List<ResultSlot> slots = ResultSlotSchedule.slots(DRAW);

    assertThat(slots).hasSize(12);
    assertThat(slots.get(0))
        .isEqualTo(new ResultSlot(Instant.parse("2026-11-14T12:30:00Z"), true, false));
    assertThat(slots.get(4).at()).isEqualTo(Instant.parse("2026-11-14T14:30:00Z"));
    assertThat(slots.get(5).at()).isEqualTo(Instant.parse("2026-11-15T00:00:00Z"));
    assertThat(slots.get(11))
        .isEqualTo(new ResultSlot(Instant.parse("2026-11-15T03:00:00Z"), true, true));
    assertThat(slots.stream().filter(ResultSlot::alertOnFailure)).hasSize(4);
  }

  @Test
  @DisplayName("다음 슬롯은 지금 이후 첫 슬롯이고 12:00이 지나면 없다")
  void next() {
    assertThat(ResultSlotSchedule.nextAtOrAfter(DRAW, Instant.parse("2026-11-14T12:31:00Z")))
        .map(ResultSlot::at)
        .contains(Instant.parse("2026-11-14T13:00:00Z"));
    assertThat(ResultSlotSchedule.nextAtOrAfter(DRAW, Instant.parse("2026-11-15T03:00:01Z")))
        .isEmpty();
  }
}
