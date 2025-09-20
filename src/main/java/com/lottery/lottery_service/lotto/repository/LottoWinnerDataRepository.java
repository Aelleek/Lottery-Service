package com.lottery.lottery_service.lotto.repository;

import com.lottery.lottery_service.lotto.entity.LottoWinnerData;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface LottoWinnerDataRepository extends JpaRepository<LottoWinnerData, Integer> {

    /** 저장된 데이터 중 가장 최신 회차 */
    Optional<LottoWinnerData> findTopByOrderByRoundDesc();

    /** 특정 회차 단건 조회 */
    Optional<LottoWinnerData> findByRound(Integer round);

}
