package com.lottery.lottery_service.result.web;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.SerializationFeature;
import com.lottery.lottery_service.common.error.ApiExceptionHandler;
import com.lottery.lottery_service.common.number.LottoNumber;
import com.lottery.lottery_service.common.number.LottoNumbers;
import com.lottery.lottery_service.result.domain.WinningNumbers;
import com.lottery.lottery_service.result.evaluate.InstantCheck;
import com.lottery.lottery_service.result.query.Prize;
import com.lottery.lottery_service.result.query.ResultState;
import com.lottery.lottery_service.result.query.RoundResult;
import com.lottery.lottery_service.result.query.RoundResultQuery;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class RoundResultControllerTest {

  private static final Instant CHECKED = Instant.parse("2026-11-14T11:45:00Z");
  private static final RoundResult PUBLISHED =
      new RoundResult(
          1200,
          ResultState.PUBLISHED,
          3L,
          new WinningNumbers(LottoNumbers.of(1, 2, 3, 4, 5, 6), LottoNumber.of(7)),
          List.of(
              new Prize(1, 2_000_000_000L, 12L),
              new Prize(2, null, null),
              new Prize(3, null, null),
              new Prize(4, 50_000L, null),
              new Prize(5, 5_000L, null)),
          CHECKED);

  private final RoundResultQuery query = mock(RoundResultQuery.class);
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    when(query.find(anyInt())).thenReturn(Optional.empty());
    Clock clock = Clock.fixed(CHECKED, ZoneOffset.UTC);
    mvc =
        MockMvcBuilders.standaloneSetup(new RoundResultController(query, new InstantCheck(query)))
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
  @DisplayName("게시된 회차는 번호·보너스·게시 버전(문자열)·등수별 금액(모르면 null)을 준다")
  void published() throws Exception {
    when(query.find(1200)).thenReturn(Optional.of(PUBLISHED));

    mvc.perform(get("/api/v1/lotto/rounds/1200/result"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.round").value(1200))
        .andExpect(jsonPath("$.state").value("PUBLISHED"))
        .andExpect(jsonPath("$.publishedRevision").value("3"))
        .andExpect(jsonPath("$.numbers[0]").value(1))
        .andExpect(jsonPath("$.numbers", hasSize(6)))
        .andExpect(jsonPath("$.bonus").value(7))
        .andExpect(jsonPath("$.prizes", hasSize(5)))
        .andExpect(jsonPath("$.prizes[0].prizePerGame").value("2000000000"))
        .andExpect(jsonPath("$.prizes[0].winnerCount").value("12"))
        .andExpect(jsonPath("$.prizes[1].prizePerGame").value(nullValue()))
        .andExpect(jsonPath("$.completeness").value("PARTIAL_PRIZES"))
        .andExpect(jsonPath("$.sourceCheckedAt").value("2026-11-14T11:45:00Z"))
        .andExpect(jsonPath("$.source").value(nullValue()))
        .andExpect(jsonPath("$.apiConfirmedAt").value(nullValue()));
  }

  @Test
  @DisplayName("직접 등록한 결과는 source MANUAL과 API 확인 시각을 준다(D-061)")
  void manualSource() throws Exception {
    when(query.find(1200))
        .thenReturn(
            Optional.of(
                new RoundResult(
                    1200,
                    ResultState.PUBLISHED,
                    3L,
                    PUBLISHED.winning(),
                    PUBLISHED.prizes(),
                    CHECKED,
                    "MANUAL",
                    null)));
    mvc.perform(get("/api/v1/lotto/rounds/1200/result"))
        .andExpect(jsonPath("$.source").value("MANUAL"))
        .andExpect(jsonPath("$.apiConfirmedAt").value(nullValue()));

    when(query.find(1200))
        .thenReturn(
            Optional.of(
                new RoundResult(
                    1200,
                    ResultState.PUBLISHED,
                    3L,
                    PUBLISHED.winning(),
                    PUBLISHED.prizes(),
                    CHECKED,
                    "MANUAL",
                    CHECKED)));
    mvc.perform(get("/api/v1/lotto/rounds/1200/result"))
        .andExpect(jsonPath("$.apiConfirmedAt").value("2026-11-14T11:45:00Z"));
  }

  @Test
  @DisplayName("게시 전 수집 지연은 200과 DELAYED이고 번호는 null, 없는 회차는 404, 0 이하는 400")
  void delayedMissingAndInvalid() throws Exception {
    when(query.find(1201))
        .thenReturn(
            Optional.of(
                new RoundResult(1201, ResultState.DELAYED, null, null, List.of(), CHECKED)));

    mvc.perform(get("/api/v1/lotto/rounds/1201/result"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("DELAYED"))
        .andExpect(jsonPath("$.publishedRevision").value(nullValue()))
        .andExpect(jsonPath("$.numbers").value(nullValue()))
        .andExpect(jsonPath("$.bonus").value(nullValue()))
        .andExpect(jsonPath("$.prizes", hasSize(0)))
        .andExpect(jsonPath("$.completeness").value("NONE"));
    mvc.perform(get("/api/v1/lotto/rounds/9999/result"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    mvc.perform(get("/api/v1/lotto/rounds/0/result"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
  }

  @Test
  @DisplayName("즉시 확인은 입력 순서대로 판정하고 번호는 오름차순, 낙첨은 EVALUATED와 rank null, 금액 미확보는 null")
  void checkPublished() throws Exception {
    when(query.find(1200)).thenReturn(Optional.of(PUBLISHED));

    mvc.perform(
            post("/api/v1/lotto/check")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"round\":1200,\"sets\":[[4,3,2,1,8,9],[1,2,3,4,5,7],[10,11,12,13,14,15]]}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.round").value(1200))
        .andExpect(jsonPath("$.items", hasSize(3)))
        .andExpect(jsonPath("$.items[0].numbers[0]").value(1))
        .andExpect(jsonPath("$.items[0].numbers[5]").value(9))
        .andExpect(jsonPath("$.items[0].evaluation.state").value("EVALUATED"))
        .andExpect(jsonPath("$.items[0].evaluation.resultRevision").value("3"))
        .andExpect(jsonPath("$.items[0].evaluation.rank").value(4))
        .andExpect(jsonPath("$.items[0].evaluation.prizePerGame").value("50000"))
        .andExpect(jsonPath("$.items[1].evaluation.rank").value(2))
        .andExpect(jsonPath("$.items[1].evaluation.prizePerGame").value(nullValue()))
        .andExpect(jsonPath("$.items[2].evaluation.state").value("EVALUATED"))
        .andExpect(jsonPath("$.items[2].evaluation.rank").value(nullValue()));
  }

  @Test
  @DisplayName("게시 전 즉시 확인은 회차 상태만 주고 등수는 null(낙첨이 아님)")
  void checkNotPublished() throws Exception {
    when(query.find(1201))
        .thenReturn(
            Optional.of(
                new RoundResult(1201, ResultState.NOT_PUBLISHED, null, null, List.of(), null)));

    mvc.perform(
            post("/api/v1/lotto/check")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"round\":1201,\"sets\":[[1,2,3,4,5,6]]}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].evaluation.state").value("NOT_PUBLISHED"))
        .andExpect(jsonPath("$.items[0].evaluation.resultRevision").value(nullValue()))
        .andExpect(jsonPath("$.items[0].evaluation.rank").value(nullValue()));
  }

  @Test
  @DisplayName("즉시 확인 입력 오류: 번호 규칙 위반 422, 세트 0개·6개와 회차 없음·모르는 회차는 400")
  void checkInvalid() throws Exception {
    when(query.find(1200)).thenReturn(Optional.of(PUBLISHED));
    String sixSets = "[1,2,3,4,5,6],".repeat(5) + "[1,2,3,4,5,6]";

    expectCheck("{\"round\":1200,\"sets\":[[1,2,3,4,5,5]]}", 422);
    expectCheck("{\"round\":1200,\"sets\":[[1,2,3,4,5,46]]}", 422);
    expectCheck("{\"round\":1200,\"sets\":[]}", 400);
    expectCheck("{\"round\":1200,\"sets\":[" + sixSets + "]}", 400);
    expectCheck("{\"sets\":[[1,2,3,4,5,6]]}", 400);
    expectCheck("{\"round\":1200}", 400);
    expectCheck("{\"round\":9999,\"sets\":[[1,2,3,4,5,6]]}", 400);
  }

  private void expectCheck(String body, int status) throws Exception {
    mvc.perform(post("/api/v1/lotto/check").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().is(status))
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
  }
}
