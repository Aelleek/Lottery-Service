//package com.lottery.lottery_service.lotto.controller;
//
//import com.fasterxml.jackson.databind.ObjectMapper;
//import com.lottery.lottery_service.lotto.dto.request.PurchaseLottoRequest;
//import com.lottery.lottery_service.lotto.entity.LottoRecord;
//import com.lottery.lottery_service.lotto.repository.LottoRecordRepository;
//import com.lottery.lottery_service.member.entity.Member;
//import com.lottery.lottery_service.member.repository.MemberRepository;
//import org.junit.jupiter.api.DisplayName;
//import org.junit.jupiter.api.Test;
//import org.springframework.beans.factory.annotation.Autowired;
//import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
//import org.springframework.boot.test.context.SpringBootTest;
//import org.springframework.http.MediaType;
//import org.springframework.test.web.servlet.MockMvc;
//import org.springframework.transaction.annotation.Transactional;
//
//import java.util.List;
//
//import static org.assertj.core.api.Assertions.assertThat;
//import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
//import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
//
//@SpringBootTest
//@AutoConfigureMockMvc
//@Transactional
//class LottoControllerIntegrationTest {
//
//    @Autowired private MockMvc mockMvc;
//    @Autowired private ObjectMapper objectMapper;
//    @Autowired private MemberRepository memberRepository;
//    @Autowired private LottoRecordRepository lottoRecordRepository;
//
////    private Member createUniqueMember() {
////        return memberRepository.save(new Member());
////    }
//
//    @Test
//    @DisplayName("① 회원 추천: 5세트 저장되고 guest=false로 기록된다")
//    void recommendLottoForMember_shouldSaveCorrectly() throws Exception {
//        Member member = createUniqueMember();
//        int beforeSize = lottoRecordRepository.findAll().size();
//
//        mockMvc.perform(post("/api/lotto/members/{memberId}/recommendations", member.getId())
//                        .param("source", "AD"))
//                .andExpect(status().isOk())
//                .andExpect(jsonPath("$.length()").value(5));
//
//        int afterSize = lottoRecordRepository.findAll().size();
//        List<LottoRecord> newRecords = lottoRecordRepository.findAll().subList(beforeSize, afterSize);
//
//        assertThat(afterSize - beforeSize).isEqualTo(5);
//        assertThat(newRecords).allMatch(r ->
//                r.getMember() != null &&
//                        !r.isGuest() &&
//                        !r.isManual() &&
//                        !r.isPurchased() &&
//                        r.getSource().equals("AD")
//        );
//    }
//
//    @Test
//    @DisplayName("② 비회원 추천: 5세트 저장되고 guest=true로 기록된다")
//    void recommendLottoForGuest_shouldSaveCorrectly() throws Exception {
//        int beforeSize = lottoRecordRepository.findAll().size();
//
//        mockMvc.perform(post("/api/lotto/guests/recommendations")
//                        .param("source", "BASIC"))
//                .andExpect(status().isOk())
//                .andExpect(jsonPath("$.length()").value(5));
//
//        int afterSize = lottoRecordRepository.findAll().size();
//        List<LottoRecord> newRecords = lottoRecordRepository.findAll().subList(beforeSize, afterSize);
//
//        assertThat(afterSize - beforeSize).isEqualTo(5);
//        assertThat(newRecords).allMatch(r ->
//                r.getMember() == null &&
//                        r.isGuest() &&
//                        !r.isManual() &&
//                        !r.isPurchased() &&
//                        r.getSource().equals("BASIC")
//        );
//    }
//
//    @Test
//    @DisplayName("③ 회원이 구매한 번호 저장 시 purchased=true, manual=true로 기록된다")
//    void addPurchasedLotto_shouldSaveAsManualPurchase() throws Exception {
//        Member member = createUniqueMember();
//        int beforeSize = lottoRecordRepository.findAll().size();
//
//        PurchaseLottoRequest request = new PurchaseLottoRequest();
//        request.setRound("1102");
//        request.setNumbersList(List.of("3, 8, 14, 22, 33, 41"));
//
//        mockMvc.perform(post("/api/lotto/{memberId}/purchases", member.getId())
//                        .contentType(MediaType.APPLICATION_JSON)
//                        .content(objectMapper.writeValueAsString(request)))
//                .andExpect(status().isOk());
//
//        int afterSize = lottoRecordRepository.findAll().size();
//        assertThat(afterSize - beforeSize).isEqualTo(1);
//
//        LottoRecord record = lottoRecordRepository.findAll().get(afterSize - 1);
//        assertThat(record.getMember().getId()).isEqualTo(member.getId());
//        assertThat(record.isPurchased()).isTrue();
//        assertThat(record.isManual()).isTrue();
//        assertThat(record.getSource()).isEqualTo("manual");
//        assertThat(record.getNumbers()).isEqualTo("3, 8, 14, 22, 33, 41");
//    }
//
//    @Test
//    @DisplayName("④ 추천 내역 조회: 저장된 추천 번호가 반환된다")
//    void getRecommendations_shouldReturnSavedRecords() throws Exception {
//        Member member = createUniqueMember();
//
//        mockMvc.perform(post("/api/lotto/members/{memberId}/recommendations", member.getId())
//                        .param("source", "EVENT"))
//                .andExpect(status().isOk());
//
//        mockMvc.perform(get("/api/lotto/members/{memberId}/recommendations", member.getId()))
//                .andExpect(status().isOk())
//                .andExpect(jsonPath("$.length()").value(5))
//                .andExpect(jsonPath("$[0].round").exists())
//                .andExpect(jsonPath("$[0].numbers").exists());
//    }
//}