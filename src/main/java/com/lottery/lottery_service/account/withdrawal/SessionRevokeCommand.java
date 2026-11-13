package com.lottery.lottery_service.account.withdrawal;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

/**
 * 보안 사고 때 모든 회원 세션을 회수하는 서버 관리 명령(07 7절 2번, C-32). {@code --lottery.incident.revoke-sessions=true}로
 * 실행하면 서버 세션을 모두 지우고 모든 회원의 {@code auth_version}을 올린 뒤 끝난다. 회원은 다시 로그인해야 한다. 비회원 자격은 이 명령이 아니라 자격
 * 해시 키의 옛 버전을 지워 무효로 한다. 웹 서버와 주기 작업은 켜지지 않는다({@code ServerCommandEnvironment}).
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "lottery.incident", name = "revoke-sessions", havingValue = "true")
public class SessionRevokeCommand implements ApplicationRunner {

  private final RestoreReplay sessions;
  private final ConfigurableApplicationContext context;

  /** 명령을 만든다. */
  public SessionRevokeCommand(RestoreReplay sessions, ConfigurableApplicationContext context) {
    this.sessions = sessions;
    this.context = context;
  }

  @Override
  public void run(ApplicationArguments args) {
    int code = execute();
    System.exit(SpringApplication.exit(context, () -> code));
  }

  /** 모든 회원 세션을 회수하고 0을 준다. */
  public int execute() {
    int members = sessions.revokeAllSessions();
    log.info("all member sessions revoked; authVersionBumpedMembers={}", members);
    return 0;
  }
}
