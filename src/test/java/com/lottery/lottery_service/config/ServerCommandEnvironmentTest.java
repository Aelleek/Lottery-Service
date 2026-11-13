package com.lottery.lottery_service.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

class ServerCommandEnvironmentTest {

  @Test
  @DisplayName("복원·권한 부여·키 재암호화·세션 회수 명령이면 웹 서버와 주기 작업을 끄고, 아니면 건드리지 않는다")
  void disablesWebAndWorkerForCommands() {
    assertThat(
            apply(
                Map.of("lottery.restore.replay-journal", "true", "lottery.worker.enabled", "true")))
        .containsEntry("web", "none")
        .containsEntry("worker", "false");
    assertThat(apply(Map.of("lottery.admin.grant.member", "x"))).containsEntry("web", "none");
    assertThat(apply(Map.of("lottery.incident.revoke-sessions", "true")))
        .containsEntry("web", "none")
        .containsEntry("worker", "false");
    assertThat(apply(Map.of("lottery.incident.revoke-sessions", "false"))).containsEntry("web", "");
    assertThat(apply(Map.of("lottery.secrets.reencrypt.ring", "contact")))
        .containsEntry("web", "none")
        .containsEntry("worker", "false");
    assertThat(
            apply(
                Map.of(
                    "lottery.restore.replay-journal", "false", "lottery.worker.enabled", "true")))
        .containsEntry("web", "")
        .containsEntry("worker", "true");
  }

  private static Map<String, String> apply(Map<String, Object> props) {
    StandardEnvironment env = new StandardEnvironment();
    env.getPropertySources().addFirst(new MapPropertySource("args", props));
    new ServerCommandEnvironment().postProcessEnvironment(env, new SpringApplication());
    return Map.of(
        "web", env.getProperty("spring.main.web-application-type", ""),
        "worker", env.getProperty("lottery.worker.enabled", ""));
  }
}
