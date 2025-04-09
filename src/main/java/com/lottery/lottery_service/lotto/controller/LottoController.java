package com.lottery.lottery_service.lotto.controller;

import com.lottery.lottery_service.lotto.dto.LottoResponseDto;
import com.lottery.lottery_service.lotto.service.LottoService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/lotto")
public class LottoController {

    private final LottoService lottoService;

    public LottoController(LottoService lottoService){
        this.lottoService = lottoService;
    }

    @GetMapping
    public LottoResponseDto generateLottoNums(){
        return new LottoResponseDto(lottoService.generateLottoNumbersSet(5));
    }

}
