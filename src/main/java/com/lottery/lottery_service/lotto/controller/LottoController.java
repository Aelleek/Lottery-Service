package com.lottery.lottery_service.lotto.controller;

import com.lottery.lottery_service.lotto.dto.LottoResponseDto;
import com.lottery.lottery_service.lotto.dto.LottoSet;
import com.lottery.lottery_service.lotto.service.LottoService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/lotto")
public class LottoController {

    private final LottoService lottoService;

    public LottoController(LottoService lottoService){
        this.lottoService = lottoService;
    }

    @GetMapping("/generate")
    public ResponseEntity<List<LottoSet>> recommendLotto() {
        // 현재는 인증 기능 미구현 상태
        boolean isMember = false;

        // 1. 로또 번호 생성
        List<LottoSet> sets = lottoService.generateLottoNumbersSet(5);

        // 2. 회원일 경우에만 저장 로직 실행
        if (isMember) {
            int currentRound = 1112; // 하드코딩된 회차
            lottoService.saveLottoNumbers(null, sets, currentRound);
        }

        // 3. 생성된 번호 반환
        return ResponseEntity.ok(sets);
    }
}

