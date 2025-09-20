package com.lottery.lottery_service.lotto.service;

import com.lottery.lottery_service.lotto.dto.request.PurchaseLottoRequest;
import com.lottery.lottery_service.lotto.dto.response.LottoRecordResponse;
import com.lottery.lottery_service.lotto.dto.LottoSet;
import com.lottery.lottery_service.lotto.entity.LottoRecord;
import com.lottery.lottery_service.member.entity.Member;
import com.lottery.lottery_service.lotto.repository.LottoRecordRepository;
import com.lottery.lottery_service.member.repository.MemberRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

import com.lottery.lottery_service.lotto.validation.pipeline.LottoValidationPipeline;
import com.lottery.lottery_service.lotto.validation.pipeline.LottoValidationResult;

/**
 * LottoService: 로또 번호 추천 및 저장 서비스 로직 담당
 *
 * 추천 생성 → 내부 검증(ValidationPipeline) → 통과 세트만 반환.
 * - 검증 결과/사유는 "내부 전용"으로만 사용하고, 사용자 응답에는 노출하지 않는다.
 *
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class LottoService {

    private final LottoRecordRepository lottoRecordRepository;
    private final MemberRepository memberRepository;
    private final LottoValidationPipeline validationPipeline;


    /**
     * 로또 번호 n세트를 생성합니다.
     * @param count 생성할 세트 수
     * @return 추천된 로또 번호 세트 리스트
     */
    public List<LottoSet> generateLottoNumbersSet(int count) {
        List<LottoSet> result = new ArrayList<>();
        Random random = new Random();

        for (int i = 0; i < count; i++) {
            Set<Integer> numbers = new HashSet<>();

            while (numbers.size() < 6) {
                numbers.add(random.nextInt(45) + 1);
            }

            List<Integer> singleSet = new ArrayList<>(numbers);
            Collections.sort(singleSet);
            result.add(new LottoSet(singleSet));
        }

        return result;
    }

    /**
     * 비회원이 추천받은 로또 번호를 저장하는 메서드.
     *
     * <p>회원 정보 없이 추천받은 번호를 저장하며,
     * 유입 방식(source)에 따라 일반 추천(BASIC), 광고 추천(AD) 등을 구분해 기록한다.</p>
     *
     * @param sets 추천받은 로또 번호 세트 목록 (각 세트는 6개의 숫자를 포함)
     * @param round 저장 대상 로또 회차
     * @param source 추천 방식 (예: "BASIC", "AD", "EVENT" 등)
     */
    public void saveLottoForGuest(List<LottoSet> sets, int round, String source) {
        List<LottoRecord> toSave = sets.stream()
                .map(set -> LottoRecord.builder()
                        .member(null)                  // 비회원이므로 null
                        .guest(true)
                        .numbers(set.getNumbers().stream()
                                .map(String::valueOf)
                                .collect(Collectors.joining(" ")))
                        .round(round)
                        .recommendedAt(LocalDateTime.now())
                        .manual(false)
                        .purchased(false)
                        .source(source)                  // 예: BASIC, AD
                        .build())
                .collect(Collectors.toList());

        lottoRecordRepository.saveAll(toSave);
    }

    /**
     * 회원이 추천받은 로또 번호를 저장하는 메서드.
     *
     * <p>Member 객체의 ID를 저장하고, 추천 방식(source)을 기록함으로써
     * 이후 당첨 분석, 추천 통계 등 다양한 데이터 분석에 활용할 수 있도록 한다.</p>
     *
     * @param memberId 추천을 받은 회원 객체 (ID 필수)
     * @param sets 추천받은 로또 번호 세트 목록
     * @param round 저장 대상 로또 회차
     * @param source 추천 방식 (예: "BASIC", "AD", "EVENT")
     */

    public void saveLottoForMember(Long memberId, List<LottoSet> sets, int round, String source) {
        // 1. 회원 조회 및 검증
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 회원입니다."));

        // 2. 추천 번호 세트
        List<LottoRecord> toSave = sets.stream()
                .map(set -> LottoRecord.builder()
                        .member(member)                    // @ManyToOne 관계 설정
                        .guest(false)                    // 회원이므로 false
                        .numbers(set.getNumbers().stream()
                                .map(String::valueOf)
                                .collect(Collectors.joining(" ")))
                        .round(round)
                        .recommendedAt(LocalDateTime.now())
                        .manual(false)
                        .purchased(false)
                        .source(source)
                        .build())
                .collect(Collectors.toList());

        // 3. 일괄 저장
        lottoRecordRepository.saveAll(toSave);
    }

    /**
     * 회원이 추천받은 로또 번호 내역을 회차 내림차순으로 조회합니다.
     *
     * @param memberId 회원 ID
     * @return 추천 내역 응답 리스트
     */
    public List<LottoRecordResponse> getRecommendationsForMember(Long memberId) {
        return lottoRecordRepository.findAllByMemberIdOrderByRoundDesc(memberId).stream()
                .map(LottoRecordResponse::from)
                .collect(Collectors.toList());
    }

    /**
     * 구매한 로또 번호 목록을 저장합니다.
     *
     * @param member 회원
     * @param request  구매 요청 DTO
     */
    public void addPurchasedRecords(Member member, PurchaseLottoRequest request) {
        int round = Integer.parseInt(request.getRound());
        List<String> numbersList = request.getNumbersList();

        for (String numbers : numbersList) {
            String normalized = normalizeNumbers(numbers);

            Optional<LottoRecord> optional = lottoRecordRepository
                    .findByMemberAndRoundAndNumbers(member, round, normalized);

            if (optional.isPresent()) {
                LottoRecord existing = optional.get();
                if (!existing.isPurchased()) {
                    existing.setPurchased(true);
                    lottoRecordRepository.save(existing);
                }
            } else {
                LottoRecord newRecord = LottoRecord.builder()
                        .member(member)
                        .round(round)
                        .numbers(normalized)
                        .recommendedAt(LocalDateTime.now())
                        .manual(true)
                        .purchased(true)
                        .source("manual")
                        .build();

                lottoRecordRepository.save(newRecord);
            }
        }
    }

    /**
     * 인증된 회원에게 로또 번호 5세트를 추천하고 저장합니다.
     *
     * <p>컨트롤러를 얇게 유지하기 위해, "세트 생성 → 회차결정 → 저장"을 한 번에 처리합니다.
     * 저장 자체는 {@link #saveLottoForMember(Long, List, int, String)}를 호출합니다.
     *
     * @param memberId 인증된 회원 식별자
     * @param source   추천 요청 출처(BASIC/AD/EVENT)
     * @return 추천된 로또 번호 세트 목록
     * @throws IllegalArgumentException 회원을 찾을 수 없는 경우
     */
    public List<LottoSet> recommendAndSaveForMember(Long memberId, String source) {
        // 기존처럼 한 번에 5세트를 생성하되,
        // 검증을 통과한 세트만 수집되도록 루프/재시도로 채움.
        final int desired = 5;
        final int maxAttempts = desired * 20; // 무한루프 방지용 여유치

        List<LottoSet> sets = new ArrayList<>(desired);
        int attempts = 0;

        while (sets.size() < desired && attempts++ < maxAttempts) {
            LottoSet candidate = generateLottoNumbersSet(1).get(0);

            // [ADDED] 내부 검증 파이프라인 호출 (불허면 폐기하고 다시 생성)
            LottoValidationResult vr = validationPipeline.validate(candidate);
            if (vr.isPass()) {
                sets.add(candidate);
            } else {
                // 내부 추적용 로그만 (외부 응답에 노출하지 않음)
                log.info("validation failed (member): rule={}, reason={}", vr.getFailedRuleId(), vr.getFailedReasonCode());
            }
        }

        int currentRound = 1112; // TODO: 동적 계산/외부 API로 교체
        saveLottoForMember(memberId, sets, currentRound, source);
        return sets;
    }

    /**
     * 비회원(게스트)에게 로또 번호 5세트를 추천하고 저장합니다.
     *
     * <p>컨트롤러에서 세트 생성/회차결정 로직을 제거하기 위해 서비스에서 한 번에 처리합니다.
     * 저장 자체는 {@link #saveLottoForGuest(List, int, String)}를 호출합니다.
     *
     * @param source 추천 요청 출처(BASIC/AD/EVENT)
     * @return 추천된 로또 번호 세트 목록
     */
    // === CHANGED START: 신규 오케스트레이터(게스트) 추가 ===
    public List<LottoSet> recommendAndSaveForGuest(String source) {
        List<LottoSet> sets = generateLottoNumbersSet(5);
        int currentRound = 1112; // TODO: 동적 계산/외부 API로 교체
        saveLottoForGuest(sets, currentRound, source);
        return sets;
    }

    /**
     * 사용자 입력 번호 문자열을 내부 저장 포맷("1 2 3 4 5 6")으로 정규화한다.
     *
     * <p>쉼표/공백 혼합 입력을 허용하며, 아래를 검증한다:
     * <ul>
     *   <li>정확히 6개 숫자</li>
     *   <li>범위 1~45</li>
     *   <li>중복 없음</li>
     * </ul>
     *
     * @param raw 예: "1, 2, 3, 4, 5, 6" 또는 "1 2 3 4 5 6"
     * @return "1 2 3 4 5 6" 형식(정렬·공백 구분)의 canonical 문자열
     * @throws IllegalArgumentException 파싱/검증 실패 시
     */
    private static String normalizeNumbers(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("numbers cannot be null");
        }
        // 쉼표/공백 구분 토큰화
        String[] tokens = raw.trim().split("[,\\s]+");
        if (tokens.length != 6) {
            throw new IllegalArgumentException("로또 번호는 6개여야 합니다. 입력: " + raw);
        }

        // 정수 파싱 + 검증
        java.util.Set<Integer> set = new java.util.HashSet<>();
        for (String t : tokens) {
            final int n;
            try {
                n = Integer.parseInt(t);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("숫자 형식이 올바르지 않습니다: " + raw, e);
            }
            if (n < 1 || n > 45) {
                throw new IllegalArgumentException("로또 번호는 1~45 사이여야 합니다. 입력: " + raw);
            }
            if (!set.add(n)) {
                throw new IllegalArgumentException("로또 번호에 중복이 있습니다. 입력: " + raw);
            }
        }

        // 정렬 후 " "로 조인 → canonical 포맷
        java.util.List<Integer> sorted = new java.util.ArrayList<>(set);
        java.util.Collections.sort(sorted);
        return sorted.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(" "));
    }
}
