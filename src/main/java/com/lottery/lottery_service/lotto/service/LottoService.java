package com.lottery.lottery_service.lotto.service;

import com.lottery.lottery_service.lotto.dto.LottoRecordResponse;
import com.lottery.lottery_service.lotto.dto.LottoSet;
import com.lottery.lottery_service.lotto.entity.LottoRecord;
import com.lottery.lottery_service.lotto.entity.Member;
import com.lottery.lottery_service.lotto.repository.LottoRecordRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * LottoService: 로또 번호 추천 및 저장 서비스 로직 담당
 */
@Service
@RequiredArgsConstructor
public class LottoService {

    private final LottoRecordRepository lottoRecordRepository;

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
                        .isGuest(true)
                        .numbers(set.getNumbers().stream()
                                .map(String::valueOf)
                                .collect(Collectors.joining(" ")))
                        .round(round)
                        .recommendedAt(LocalDateTime.now())
                        .isManual(false)
                        .isPurchased(false)
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
     * @param member 추천을 받은 회원 객체 (ID 필수)
     * @param sets 추천받은 로또 번호 세트 목록
     * @param round 저장 대상 로또 회차
     * @param source 추천 방식 (예: "BASIC", "AD", "EVENT")
     */

    public void saveLottoForMember(Long memberId, List<LottoSet> sets, int round, String source) {
        // 1. 회원 조회 및 검증
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 회원입니다."));

        // 2. 추천 번호 세트 → LottoRecord로 변환
        List<LottoRecord> toSave = sets.stream()
                .map(set -> LottoRecord.builder()
                        .member(member)                    // @ManyToOne 관계 설정
                        .isGuest(false)                    // 회원이므로 false
                        .numbers(set.getNumbers().stream()
                                .map(String::valueOf)
                                .collect(Collectors.joining(" ")))
                        .round(round)
                        .recommendedAt(LocalDateTime.now())
                        .isManual(false)
                        .isPurchased(false)
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

}
