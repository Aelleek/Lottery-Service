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
 * 세션을 회수한 뒤 끝난다. 삭제 기록 저장소가 없거나 읽지 못하면 실패로 끝나며, 그때는 서비스를 열지 않는다.
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
    DeletionJournalReader reader = journal.getIfAvailable();
    if (reader == null) {
      log.error("deletion journal reader is not configured; do not open the service");
      System.exit(SpringApplication.exit(context, () -> 2));
      return;
    }
    RestoreReplay.Result result = replay.replay(reader);
    int revoked = replay.revokeAllSessions();
    log.info(
        "restore replay blocked={} alreadyHandled={} missing={} sessionsRevokedMembers={}",
        result.blocked(),
        result.alreadyHandled(),
        result.missing(),
        revoked);
    System.exit(SpringApplication.exit(context, () -> 0));
  }
}
