package com.lottery.lottery_service.account.withdrawal;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

/**
 * 복원 뒤 서버 관리 명령(07 4절 3·4번). {@code --lottery.restore.replay-journal=true}로 실행하면 삭제 기록을 다시 적용하고 모든
 * 세션을 회수한 뒤 끝난다. 삭제 기록 저장소가 없거나 읽지 못하면 실패로 끝나며, 그때는 서비스를 열지 않는다. 이 명령으로 실행하면 웹 서버와 주기 작업은 켜지지
 * 않는다({@code ServerCommandEnvironment}).
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "lottery.restore", name = "replay-journal", havingValue = "true")
public class RestoreCommand implements ApplicationRunner {

  private final RestoreReplay replay;
  private final ObjectProvider<DeletionJournalReader> journal;
  private final ConfigurableApplicationContext context;

  /** 명령을 만든다. */
  public RestoreCommand(
      RestoreReplay replay,
      ObjectProvider<DeletionJournalReader> journal,
      ConfigurableApplicationContext context) {
    this.replay = replay;
    this.journal = journal;
    this.context = context;
  }

  @Override
  public void run(ApplicationArguments args) {
    int code = execute();
    System.exit(SpringApplication.exit(context, () -> code));
  }

  /** 삭제 기록을 다시 적용하고 세션을 회수한다. 삭제 기록 저장소가 없으면 아무것도 바꾸지 않고 2를 준다. 읽다가 실패하면 예외를 그대로 던진다. */
  int execute() {
    DeletionJournalReader reader = journal.getIfAvailable();
    if (reader == null) {
      log.error("deletion journal reader is not configured; do not open the service");
      return 2;
    }
    RestoreReplay.Result result = replay.replay(reader);
    int revoked = replay.revokeAllSessions();
    log.info(
        "restore replay blocked={} alreadyHandled={} missing={} sessionsRevokedMembers={}",
        result.blocked(),
        result.alreadyHandled(),
        result.missing(),
        revoked);
    return 0;
  }
}
