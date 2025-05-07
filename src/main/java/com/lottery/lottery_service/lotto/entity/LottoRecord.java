package com.lottery.lottery_service.lotto.entity;

import com.lottery.lottery_service.lotto.dto.LottoSet;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 추천받은 로또 번호 정보를 저장하는 엔티티 클래스입니다.
 *
 * <p>회원/비회원 여부, 추천 방식(source), 추천 시간 등
 * 추천 내역에 대한 다양한 정보를 함께 저장합니다.</p>
 */
@Entity
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LottoRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 회원 ID (비회원일 경우 null)
     */
    private Long memberId;

    /**
     * 비회원 추천 여부
     */
    private boolean isGuest;

    /**
     * 추천된 로또 번호를 공백 문자열로 저장 (예: "1 5 12 24 30 43")
     */
    private String numbers;

    /**
     * 로또 회차
     */
    private int round;

    /**
     * 추천 받은 시간
     */
    private LocalDateTime recommendedAt;

    /**
     * 수동 입력 여부 (true: 직접 입력, false: 자동 추천)
     */
    private boolean isManual;

    /**
     * 구매 여부 (true: 사용자가 해당 번호를 구매함)
     */
    private boolean isPurchased;

    /**
     * 추천 방식 (예: BASIC, AD, EVENT 등)
     */
    private String source;

    /**
     * 로또 번호를 객체로 가져올 수 있도록 변환합니다.
     *
     * @return LottoSet 형태로 파싱된 로또 번호 세트
     */
    @Transient
    public LottoSet getLottoSet() {
        List<Integer> list = Arrays.stream(this.numbers.split(" "))
                .map(Integer::parseInt)
                .collect(Collectors.toList());
        return new LottoSet(list);
    }

    /**
     * LottoSet 형태의 번호를 문자열로 저장하기 위한 변환 메서드입니다.
     *
     * @param set LottoSet 객체
     */
    public void setFromLottoSet(LottoSet set) {
        this.numbers = set.getNumbers().stream()
                .map(String::valueOf)
                .collect(Collectors.joining(" "));
    }
}

