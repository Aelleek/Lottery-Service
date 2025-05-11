package com.lottery.lottery_service.lotto.controller;

import com.lottery.lottery_service.lotto.dto.request.PurchaseLottoRequest;
import com.lottery.lottery_service.lotto.dto.response.LottoRecordResponse;
import com.lottery.lottery_service.lotto.dto.LottoSet;
import com.lottery.lottery_service.lotto.service.LottoService;
import com.lottery.lottery_service.member.entity.Member;
import com.lottery.lottery_service.member.repository.MemberRepository;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/lotto")
public class LottoController {

    private final LottoService lottoService;
    private final MemberRepository memberRepository;

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
    @PostMapping("/members/{memberId}/recommendations")
    public ResponseEntity<List<LottoSet>> recommendLottoForMember(
            @PathVariable Long memberId,
            @RequestParam(defaultValue = "BASIC") String source) {
        return ResponseEntity.ok(recommendAndSave(true, memberId, source));
    }

    /**
     * 비회원에게 로또 번호를 추천하고 저장합니다.
     *
     * @param source 추천 방식 (기본값: BASIC)
     * @return 추천된 로또 번호 세트 리스트
     */
    @PostMapping("/guests/recommendations")
    public ResponseEntity<List<LottoSet>> recommendLottoForGuest(
            @RequestParam(defaultValue = "BASIC") String source) {
        return ResponseEntity.ok(recommendAndSave(false, null, source));
    }

    /**
     * 추천된 로또 번호를 생성하고 저장하는 내부 공통 메소드입니다.
     *
     * @param isMember 회원 여부
     * @param memberId 회원 ID (비회원일 경우 null)
     * @param source 추천 방식
     * @return 생성된 로또 번호 세트 리스트
     */
    private List<LottoSet> recommendAndSave(boolean isMember, Long memberId, String source) {

        int currentRound = 1112; // TODO: 추후 현재 회차 자동 처리 로직으로 대체
        List<LottoSet> sets = lottoService.generateLottoNumbersSet(5);

        if (isMember) {
            lottoService.saveLottoForMember(memberId, sets, currentRound, source);
        } else {
            lottoService.saveLottoForGuest(sets, currentRound, source);
        }

        return sets;
    }

    /**
     * 회원의 추천받은 로또 번호 내역을 조회합니다.
     *
     * <p>memberId를 경로 변수로 받아 해당 회원의 추천 내역을 회차 기준 내림차순으로 반환합니다.</p>
     *
     * @param memberId 회원 ID
     * @return 추천 내역 리스트
     */
    @GetMapping("/members/{memberId}/recommendations")
    public ResponseEntity<List<LottoRecordResponse>> getLottoRecommendations(
            @PathVariable Long memberId) {
        return ResponseEntity.ok(lottoService.getRecommendationsForMember(memberId));
    }


    /**
     * 회원의 구매 번호를 저장하는 API
     *
     * @param memberId 회원 ID
     * @param request  구매 번호 요청 DTO
     * @return 저장 완료 응답
     */
    @PostMapping("/{memberId}/purchases")
    public ResponseEntity<String> addPurchasedLotto(
            @PathVariable Long memberId,
            @Valid @RequestBody PurchaseLottoRequest request) {

        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new IllegalArgumentException("해당 회원이 존재하지 않습니다."));

        lottoService.addPurchasedRecords(member, request);

        return ResponseEntity.ok("구매 번호가 성공적으로 저장되었습니다.");
    }
}

