package com.lottery.lottery_service.record;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class HousekeepingOrphanBatchTest {

  @Test
  @DisplayName("어느 표든 상한만큼 지웠으면 남은 것이 있을 수 있다고 보고, 모두 상한보다 적으면 끝난 것으로 본다")
  void full() {
    assertThat(new HousekeepingCleaner.OrphanBatch(9, 9, 9).full(10)).isFalse();
    assertThat(new HousekeepingCleaner.OrphanBatch(10, 0, 0).full(10)).isTrue();
    assertThat(new HousekeepingCleaner.OrphanBatch(0, 10, 0).full(10)).isTrue();
    assertThat(new HousekeepingCleaner.OrphanBatch(0, 0, 10).full(10)).isTrue();
  }
}
