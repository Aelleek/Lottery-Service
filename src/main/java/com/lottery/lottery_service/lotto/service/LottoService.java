package com.lottery.lottery_service.lotto.service;

import com.lottery.lottery_service.lotto.dto.LottoSet;
import com.lottery.lottery_service.lotto.entity.LottoNumber;
import com.lottery.lottery_service.lotto.entity.Member;
import com.lottery.lottery_service.lotto.repository.LottoRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;

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

    public void saveLottoNumbers(Member member, List<LottoSet> sets, int round) {
        if (member == null) return; // 회원이 없으면 저장 생략

        List<LottoNumber> toSave = new ArrayList<>();
        for (LottoSet set : sets) {
            LottoNumber entity = new LottoNumber(set.getNumbers(), round, member);
            toSave.add(entity);
        }

        lottoRepository.saveAll(toSave);
    }
}
