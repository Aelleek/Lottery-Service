package com.lottery.lottery_service.config;

import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * 서버 관리 명령(복원 뒤 삭제 기록 재적용, 관리자 권한 부여, 키 재암호화)으로 실행할 때는 웹 서버와 주기 작업을 켜지 않는다. 명령이 끝나기 전에 요청을 받거나 발송이
 * 돌지 않게 한다(07 4절 1·3번).
 */
public class ServerCommandEnvironment implements EnvironmentPostProcessor {

  /** 이 값들을 다른 설정보다 먼저 본다. */
  static final String SOURCE = "lotteryServerCommand";

  @Override
  public void postProcessEnvironment(
      ConfigurableEnvironment environment, SpringApplication application) {
    if (!isCommand(environment)) {
      return;
    }
    environment
        .getPropertySources()
        .addFirst(
            new MapPropertySource(
                SOURCE,
                Map.of(
                    "spring.main.web-application-type", "none",
                    "lottery.worker.enabled", "false")));
  }

  /** 서버 관리 명령으로 실행했는지. */
  static boolean isCommand(ConfigurableEnvironment environment) {
    return "true".equalsIgnoreCase(environment.getProperty("lottery.restore.replay-journal"))
        || environment.containsProperty("lottery.admin.grant.member")
        || environment.containsProperty("lottery.secrets.reencrypt.ring");
  }
}
