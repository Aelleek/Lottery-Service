package com.lottery.lottery_service.lotto.repository;

import com.lottery.lottery_service.lotto.entity.LottoRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 추천받은 로또 번호 내역을 조회하고 저장하는 JPA Repository.
 */
@Repository
public interface LottoRecordRepository extends JpaRepository<LottoRecord, Long> {

    /**
     * 회원이 추천 받은 로또 번호 목록을 회차 기준 내림차순으로 조회합니다.
     *
     * @param memberId 조회 대상 회원의 ID
     * @return 추천 내역 리스트 (최신 회차 우선 정렬)
     */
    List<LottoRecord> findAllByMemberIdOrderByRoundDesc(Long memberId);
    
    /**
     * 해당 회원이 특정 회차에 추천 받은 횟수를 반환합니다.
     *
     * @param memberId 회원 ID
     * @param round 회차 번호
     * @return 추천받은 횟수
     */
    long countByMemberIdAndRound(Long memberId, int round);
}
