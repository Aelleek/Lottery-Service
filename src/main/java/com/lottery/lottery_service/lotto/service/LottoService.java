package com.lottery.lottery_service.lotto.service;

import com.lottery.lottery_service.lotto.dto.LottoSet;
import com.lottery.lottery_service.lotto.entity.LottoHistory;
import com.lottery.lottery_service.lotto.entity.LottoNumber;
import com.lottery.lottery_service.lotto.entity.Member;
import com.lottery.lottery_service.lotto.repository.LottoRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class LottoService {
    @Autowired
    private LottoRepository lottoRepository;


    public List<LottoSet> generateLottoNumbersSet(int count) {
        List<LottoSet> result = new ArrayList<>();

        for (int i = 0; i < count; i++) {
            Set<Integer> numbers = new HashSet<>();
            Random random = new Random();

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
        List<LottoHistory> toSave = sets.stream()
                .map(set -> LottoHistory.builder()
                        .memberId(null)                  // 비회원이므로 null
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

        lottoHistoryRepository.saveAll(toSave);
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

    public void saveLottoForMember(Member member, List<LottoSet> sets, int round, String source) {
        List<LottoHistory> toSave = sets.stream()
                .map(set -> LottoHistory.builder()
                        .memberId(member.getId())        // 회원 ID
                        .isGuest(false)
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

        lottoHistoryRepository.saveAll(toSave);
    }
}
