package com.lottery.lottery_service.round.web;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.SerializationFeature;
import com.lottery.lottery_service.common.error.ApiExceptionHandler;
import com.lottery.lottery_service.round.RoundStore;
import com.lottery.lottery_service.round.domain.RoundCalendar;
import com.lottery.lottery_service.round.domain.RoundSchedule;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class LottoContextControllerTest {

  private static final RoundSchedule R1200 =
      new RoundSchedule(
          1200,
          Instant.parse("2026-11-07T21:00:00Z"),
          Instant.parse("2026-11-14T11:00:00Z"),
          Instant.parse("2026-11-14T11:35:00Z"),
          3);
  private static final RoundSchedule R1201 =
      new RoundSchedule(
          1201,
          Instant.parse("2026-11-14T21:00:00Z"),
          Instant.parse("2026-11-21T11:00:00Z"),
          Instant.parse("2026-11-21T11:35:00Z"),
          1);

  private final RoundStore rounds = mock(RoundStore.class);

  private MockMvc mvc(Instant now) {
    Clock clock = Clock.fixed(now, ZoneOffset.UTC);
    return MockMvcBuilders.standaloneSetup(new LottoContextController(rounds, clock))
        .setControllerAdvice(
            new ApiExceptionHandler(
                clock, new com.lottery.lottery_service.common.web.SecuritySignals()))
        .setMessageConverters(
            new MappingJackson2HttpMessageConverter(
                Jackson2ObjectMapperBuilder.json()
                    .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                    .build()))
        .build();
  }

  @Test
  @DisplayName("판매 중이면 현재 회차와 일정 버전(문자열), 추천 가능, 다음 개시 시각 없음")
  void open() throws Exception {
    when(rounds.calendar()).thenReturn(new RoundCalendar(List.of(R1200, R1201)));

    mvc(Instant.parse("2026-11-10T00:00:00Z"))
        .perform(get("/api/v1/lotto/context"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.currentRound").value(1200))
        .andExpect(jsonPath("$.scheduleVersion").value("3"))
        .andExpect(jsonPath("$.recommendationOpen").value(true))
        .andExpect(jsonPath("$.nextOpenAt").value(nullValue()))
        .andExpect(jsonPath("$.serverTime").value("2026-11-10T00:00:00Z"));
  }

  @Test
  @DisplayName("마감 구간이면 방금 마감한 회차와 다음 개시 시각, 일정이 없으면 503 SCHEDULE_UNAVAILABLE")
  void closedAndUnavailable() throws Exception {
    when(rounds.calendar()).thenReturn(new RoundCalendar(List.of(R1200, R1201)));
    mvc(Instant.parse("2026-11-14T12:00:00Z"))
        .perform(get("/api/v1/lotto/context"))
        .andExpect(jsonPath("$.currentRound").value(1200))
        .andExpect(jsonPath("$.recommendationOpen").value(false))
        .andExpect(jsonPath("$.nextOpenAt").value("2026-11-14T21:00:00Z"));

    when(rounds.calendar()).thenReturn(new RoundCalendar(List.of()));
    mvc(Instant.parse("2026-11-14T12:00:00Z"))
        .perform(get("/api/v1/lotto/context"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value("SCHEDULE_UNAVAILABLE"));
  }
}
