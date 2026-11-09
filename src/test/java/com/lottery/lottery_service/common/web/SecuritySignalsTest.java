package com.lottery.lottery_service.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.lottery.lottery_service.common.error.ErrorCode;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SecuritySignalsTest {

  @Test
  @DisplayName("429·CSRF·출처 거절만 세고, 경로의 숫자·UUID는 {id}로 묶으며 이상한 경로는 기타로 센다")
  void countsSignalsByRoute() {
    SecuritySignals signals = new SecuritySignals();
    signals.record(ErrorCode.RATE_LIMITED, "/api/v1/lotto/rounds/1200/result");
    signals.record(ErrorCode.GENERATION_BUSY, "/api/v1/lotto/rounds/1201/result");
    signals.record(
        ErrorCode.ORIGIN_REJECTED,
        "/api/v1/me/lotto/records/0b9f1c2e-5a7d-4e3b-9c1a-2f6d8e4b7a10/cancel-purchase");
    signals.record(ErrorCode.CSRF_REJECTED, "/api/v1/x@everyone<script>");
    signals.record(ErrorCode.NOT_FOUND, "/api/v1/anything");
    signals.record(ErrorCode.VALIDATION_FAILED, "/api/v1/anything");

    Map<SecuritySignals.Kind, SecuritySignals.Summary> drained = signals.drain();

    assertThat(drained)
        .containsEntry(
            SecuritySignals.Kind.TOO_MANY_REQUESTS,
            new SecuritySignals.Summary(2, "/api/v1/lotto/rounds/{id}/result"))
        .containsEntry(
            SecuritySignals.Kind.ORIGIN_REJECTED,
            new SecuritySignals.Summary(1, "/api/v1/me/lotto/records/{id}/cancel-purchase"))
        .containsEntry(
            SecuritySignals.Kind.CSRF_REJECTED,
            new SecuritySignals.Summary(1, SecuritySignals.OTHER_PATH))
        .hasSize(3);
    assertThat(signals.drain()).isEmpty();
  }

  @Test
  @DisplayName("기억하는 경로가 상한을 넘으면 새 경로는 기타로 세어 메모리가 늘지 않는다")
  void capsDistinctPaths() {
    SecuritySignals signals = new SecuritySignals();
    for (int i = 0; i < SecuritySignals.MAX_PATHS + 20; i++) {
      signals.record(SecuritySignals.Kind.CSRF_REJECTED, "/api/v1/p" + i);
    }
    for (int i = 0; i < 30; i++) {
      signals.record(SecuritySignals.Kind.CSRF_REJECTED, "/api/v1/new" + i);
    }

    assertThat(signals.drain().get(SecuritySignals.Kind.CSRF_REJECTED))
        .isEqualTo(
            new SecuritySignals.Summary(
                SecuritySignals.MAX_PATHS + 50, SecuritySignals.OTHER_PATH));
  }
}
