package com.lottery.lottery_service.lotto.controller;

import com.lottery.lottery_service.lotto.service.LottoService;
import org.mockito.Mockito;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration
public class LottoServiceTestConfig {

    @Bean
    @Primary
    public LottoService lottoService() {
        return Mockito.mock(LottoService.class);
    }
}
