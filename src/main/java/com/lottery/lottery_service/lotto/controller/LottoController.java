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
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
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
     * <p>요청한 사용자(회원에게 로또 번호 5세트를 추천하고,
     * 추천 받은 번호는 DB에 저장된다.</p>
     *
     * <p>source 파라미터를 통해 추천 방식(BASIC, AD 등)을 구분하여
     * 저장 시 이를 기록함으로써 추천 제한 및 분석에 활용할 수 있다.</p>
     *
     * @param principal OAuth2User (로그인된 사용자)
     * @param source    추천 요청 출처(BASIC/AD/EVENT)
     * @return 추천 결과 목록
     * @throws IllegalArgumentException 로그인 상태가 아니거나 memberId 속성이 없는 경우
     *
     */
    @PostMapping("/recommendations")
    public ResponseEntity<List<LottoSet>> recommendForMember(@AuthenticationPrincipal OAuth2User principal,
            @RequestParam(name = "source", defaultValue = "BASIC") String source) {

        if (principal == null || principal.getAttribute("memberId") == null) {
            throw new IllegalArgumentException("로그인 상태가 아니거나 memberId를 확인할 수 없습니다.");
        }
        Long memberId = principal.getAttribute("memberId");
        if (memberId == null) {
            throw new IllegalArgumentException("memberId 속성을 확인할 수 없습니다.");
        }

        // 컨트롤러는 오케스트레이션 로직을 갖지 않고 서비스에 위임
        List<LottoSet> sets = lottoService.recommendAndSaveForMember(memberId, source);
        return ResponseEntity.ok(sets);
    }

    /**
     * 비회원에게 로또 번호를 추천하고 저장합니다.
     *
     * @param source 추천 방식 (기본값: BASIC)
     * @return 추천된 로또 번호 세트 리스트
     */
    @PostMapping("/guests/recommendations")
    public ResponseEntity<List<LottoSet>> recommendForGuest(
            @RequestParam(name = "source", defaultValue = "BASIC") String source) {

        // 컨트롤러는 세트 생성/회차 계산을 하지 않음 — 서비스 헬퍼로 위임
        List<LottoSet> sets = lottoService.recommendAndSaveForGuest(source);
        return ResponseEntity.ok(sets);
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

