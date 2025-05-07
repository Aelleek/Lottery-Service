package com.lottery.lottery_service.lotto.controller;

import com.lottery.lottery_service.lotto.dto.LottoRecordResponse;
import com.lottery.lottery_service.lotto.dto.LottoSet;
import com.lottery.lottery_service.lotto.service.LottoService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/lotto")
public class LottoController {

    private final LottoService lottoService;

    public LottoController(LottoService lottoService){
        this.lottoService = lottoService;
    }

    /**
     * 로또 번호 추천 API
     *
     * <p>요청한 사용자(회원 또는 비회원)에게 로또 번호 5세트를 추천하고,
     * 추천 받은 번호는 회원 여부에 따라 DB에 저장된다.</p>
     *
     * <p>source 파라미터를 통해 추천 방식(BASIC, AD 등)을 구분하여
     * 저장 시 이를 기록함으로써 추천 제한 및 분석에 활용할 수 있다.</p>
     *
     * @param source 추천 방식 (기본값: BASIC). 예: "BASIC", "AD", "EVENT"
     * @return 추천된 로또 번호 세트(List<LottoSet>)
     */
    @GetMapping("/generate")
    public ResponseEntity<List<LottoSet>> recommendLotto(
            @RequestParam(defaultValue = "BASIC") String source) {
        // 현재는 인증 기능 미구현 상태
        boolean isMember = false;
        int currentRound = 1112; // 하드코딩된 회차

        // 1. 로또 번호 생성
        List<LottoSet> sets = lottoService.generateLottoNumbersSet(5);

        // 2. 저장
        if (isMember) {
            // Member 객체는 아직 없으므로 null 처리
            lottoService.saveLottoForMember(null, sets, currentRound, source);
        } else {
            lottoService.saveLottoForGuest(sets, currentRound, source);
        }

        // 3. 생성된 번호 반환
        return ResponseEntity.ok(sets);
    }

    /**
     * 회원의 추천받은 로또 번호 내역을 조회합니다.
     *
     * @param memberId 테스트용 회원 ID (인증 미구현)
     * @return 추천 내역 리스트
     */
    @GetMapping("/recommendations")
    public ResponseEntity<List<LottoRecordResponse>> getLottoRecommendations(
            @RequestParam Long memberId) {
        return ResponseEntity.ok(lottoService.getRecommendationsForMember(memberId));
    }

}

