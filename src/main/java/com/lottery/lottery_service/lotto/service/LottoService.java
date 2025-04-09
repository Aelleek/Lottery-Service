package com.lottery.lottery_service.lotto.service;

import com.lottery.lottery_service.lotto.dto.LottoSet;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class LottoService {
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
}
