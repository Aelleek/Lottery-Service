package com.lottery.lottery_service.common.time;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 애플리케이션 전체가 주입받아 쓰는 {@link Clock}을 등록한다. 테스트는 고정 시계로 바꿔 시간 경계를 검증한다. */
@Configuration
public class ClockConfig {

  /** UTC 기준 시스템 시계. 날짜 경계는 {@link SeoulTime}으로 서울 시간에 맞춰 계산한다. */
  @Bean
  public Clock clock() {
    return Clock.systemUTC();
  }
}
