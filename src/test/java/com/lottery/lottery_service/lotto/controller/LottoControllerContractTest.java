package com.lottery.lottery_service.lotto.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lottery.lottery_service.lotto.dto.request.PurchaseLottoRequest;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** LottoControllerContractTest.java 명세 검증용 테스트 클래스 (익명 클래스로 LottoService 대체) */
@SpringBootTest
@AutoConfigureMockMvc
class LottoControllerContractTest {

  @Autowired private MockMvc mockMvc;

  @Autowired private ObjectMapper objectMapper;

  @Test
  @DisplayName("회원 추천: 로또 번호 5세트 반환")
  void recommendLottoForMember_shouldReturnValidJson() throws Exception {
    mockMvc
        .perform(post("/api/lotto/members/1/recommendations").param("source", "BASIC"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(5)) // 5세트 반환 기준
        .andExpect(jsonPath("$[0].numbers.length()").value(6));
  }

  @Test
  @DisplayName("비회원 추천: 로또 번호 5세트 반환")
  void recommendLottoForGuest_shouldReturnLottoNumbers() throws Exception {
    mockMvc
        .perform(post("/api/lotto/guests/recommendations").param("source", "AD"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(5))
        .andExpect(jsonPath("$[0].numbers.length()").value(6));
  }

  @Test
  @DisplayName("회원 추천 내역 조회: 응답 구조 확인")
  void getLottoRecommendations_shouldReturnLottoRecordList() throws Exception {
    mockMvc
        .perform(get("/api/lotto/members/1/recommendations"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$").isArray());
  }

  @Test
  @DisplayName("회원 구매 번호 저장: 200 OK 반환")
  void addPurchasedLotto_shouldReturnOk() throws Exception {
    PurchaseLottoRequest request = new PurchaseLottoRequest();
    request.setRound("1100");
    request.setNumbersList(Arrays.asList("1, 2, 3, 4, 5, 6"));

    mockMvc
        .perform(
            post("/api/lotto/1/purchases")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isOk());
  }
}
