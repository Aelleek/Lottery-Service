package com.lottery.lottery_service.config;

import com.lottery.lottery_service.admin.AdminAccess;
import com.lottery.lottery_service.admin.web.AdminSessionInterceptor;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** 관리자 API 앞에 관리자 세션 확인을 둔다. 관리자 인증 시작 경로는 뺀다. */
@Configuration
@ConditionalOnProperty(prefix = "lottery.api-v1", name = "enabled", havingValue = "true")
public class AdminWebConfig implements WebMvcConfigurer {

  private final AdminAccess access;
  private final Clock clock;

  /** 설정을 만든다. */
  public AdminWebConfig(AdminAccess access, Clock clock) {
    this.access = access;
    this.clock = clock;
  }

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry
        .addInterceptor(new AdminSessionInterceptor(access, clock))
        .addPathPatterns("/api/v1/admin/**")
        .excludePathPatterns("/api/v1/admin/session");
  }
}
