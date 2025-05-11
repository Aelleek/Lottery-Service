package com.lottery.lottery_service.lotto.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class LottoResponseDto {

    private List<LottoSet> lottoNumbers;


}
