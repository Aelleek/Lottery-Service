package com.lottery.lottery_service.lotto.dto;

import java.util.List;

public class LottoResponseDto {

    private List<LottoSet> lottoNumbers;

    public LottoResponseDto(List<LottoSet> lottoNumbers) {
        this.lottoNumbers = lottoNumbers;
    }

    public List<LottoSet> getLottoNumbers() {
        return lottoNumbers;
    }

    public void setLottoNumbers(List<LottoSet> lottoNumbers) {
        this.lottoNumbers = lottoNumbers;
    }
}
