package com.lottery.lottery_service.lotto.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lottery.lottery_service.lotto.dto.response.LottoRecordResponse;
import com.lottery.lottery_service.lotto.dto.LottoSet;
import com.lottery.lottery_service.lotto.service.LottoService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 테스트 전용 LottoService 구성 클래스
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(LottoServiceTestConfig.class)
public class LottoControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private LottoService lottoService;


    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("회원의 추천 내역 조회 - 문자열 numbers 검증")
    void getRecommendationsForMember_shouldReturn200() throws Exception {
        Long memberId = 1L;

        LottoRecordResponse response = LottoRecordResponse.builder()
                .round(1112)
                .numbers("3 8 14 22 33 41")
                .recommendedAt(LocalDateTime.now())
                .manual(false)
                .purchased(false)
                .source("BASIC")
                .build();

        Mockito.when(lottoService.getRecommendationsForMember(eq(memberId)))
                .thenReturn(List.of(response));

        mockMvc.perform(get("/api/lotto/members/{memberId}/recommendations", memberId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].round").value(1112))
                .andExpect(jsonPath("$[0].numbers").value("3 8 14 22 33 41")); // 문자열 그대로 비교
    }

    @Test
    @DisplayName("회원의 로또 추천 요청 API - 성공")
    void recommendLottoForMember_shouldReturnLottoSets() throws Exception {
        Long memberId = 1L;
        String source = "BASIC";

        List<LottoSet> generatedSets = List.of(
                new LottoSet(List.of(1, 2, 3, 4, 5, 6)),
                new LottoSet(List.of(10, 11, 12, 13, 14, 15))
        );

        Mockito.when(lottoService.generateLottoNumbersSet(eq(5)))
                .thenReturn(generatedSets);

        // doNothing for save
        Mockito.doNothing().when(lottoService).saveLottoForMember(eq(memberId), any(), eq(1112), eq(source));

        mockMvc.perform(post("/api/lotto/members/{memberId}/recommendations", memberId)
                        .param("source", source))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].numbers.length()").value(6))
                .andExpect(jsonPath("$[1].numbers[0]").value(10));
    }

    @Test
    @DisplayName("비회원의 로또 추천 요청 API - 성공")
    void recommendLottoForGuest_shouldReturnLottoSets() throws Exception {
        String source = "AD";

        List<LottoSet> generatedSets = List.of(
                new LottoSet(List.of(7, 8, 9, 10, 11, 12))
        );

        Mockito.when(lottoService.generateLottoNumbersSet(eq(5)))
                .thenReturn(generatedSets);

        Mockito.doNothing().when(lottoService).saveLottoForGuest(any(), eq(1112), eq(source));

        mockMvc.perform(post("/api/lotto/guests/recommendations")
                        .param("source", source))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].numbers[0]").value(7))
                .andExpect(jsonPath("$[0].numbers.length()").value(6));
    }
}
