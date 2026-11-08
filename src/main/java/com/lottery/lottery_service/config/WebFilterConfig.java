package com.lottery.lottery_service.config;

import com.lottery.lottery_service.common.web.BodySizeFilter;
import com.lottery.lottery_service.common.web.RateLimitFilter;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerExceptionResolver;

/** 보안 필터 체인보다 먼저 도는 요청 제한과 본문 크기 제한을 등록한다. 세션·DB를 읽기 전에 거른다. */
@Configuration
public class WebFilterConfig {

  /** IP별 요청 제한. */
  @Bean
  public FilterRegistrationBean<RateLimitFilter> rateLimitFilter(
      Clock clock, @Qualifier("handlerExceptionResolver") HandlerExceptionResolver errors) {
    FilterRegistrationBean<RateLimitFilter> bean =
        new FilterRegistrationBean<>(new RateLimitFilter(clock, errors));
    bean.setOrder(-300);
    return bean;
  }

  /** 본문 64KB 제한. */
  @Bean
  public FilterRegistrationBean<BodySizeFilter> bodySizeFilter(
      @Qualifier("handlerExceptionResolver") HandlerExceptionResolver errors) {
    FilterRegistrationBean<BodySizeFilter> bean =
        new FilterRegistrationBean<>(new BodySizeFilter(errors));
    bean.setOrder(-250);
    return bean;
  }
}
