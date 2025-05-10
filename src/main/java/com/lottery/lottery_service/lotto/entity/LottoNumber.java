package com.lottery.lottery_service.lotto.entity;

import com.lottery.lottery_service.member.entity.Member;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Entity
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class LottoNumber {

    @Id @GeneratedValue
    private Long id;

    @ElementCollection
    private List<Integer> numbers;

    private int round;

    @ManyToOne
    private Member member;

    private LocalDateTime recommendedAt = LocalDateTime.now();

    private boolean isManual = false;
    private boolean isPurchased = false;

    public LottoNumber(List<Integer> numbers, int round, Member member) {
        this.numbers = numbers;
        this.round = round;
        this.member = member;
    }
}
