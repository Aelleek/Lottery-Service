package com.lottery.lottery_service.recommendation;

import com.lottery.lottery_service.recommendation.generator.RecommendationGenerator;
import java.security.SecureRandom;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 추천 생성기를 등록한다. 난수는 {@link SecureRandom}을 쓴다. */
@Configuration
public class RecommendationConfig {

  /** 운영용 생성기. */
  @Bean
  public RecommendationGenerator recommendationGenerator() {
    return new RecommendationGenerator(new SecureRandom(), System::nanoTime);
  }
}
