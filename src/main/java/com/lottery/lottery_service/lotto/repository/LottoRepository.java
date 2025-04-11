package com.lottery.lottery_service.lotto.repository;

import com.lottery.lottery_service.lotto.entity.LottoNumber;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface LottoRepository extends JpaRepository<LottoNumber, Long> {
}
