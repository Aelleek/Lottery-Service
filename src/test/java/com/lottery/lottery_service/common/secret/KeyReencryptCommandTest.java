package com.lottery.lottery_service.common.secret;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class KeyReencryptCommandTest {

  @Test
  @DisplayName("옛 버전으로 남은 값이 없으면 0, 남으면 1, 모르는 키 묶음이면 2로 끝난다")
  void exitCodes() {
    KeyReencryptCommand command =
        new KeyReencryptCommand(
            List.of(fixed("contact", 3, 0), fixed("admin-mfa", 1, 2)), null, null);

    assertThat(command.execute("contact")).isZero();
    assertThat(command.execute("admin-mfa")).isEqualTo(1);
    assertThat(command.execute("csrf-token")).isEqualTo(2);
  }

  @Test
  @DisplayName("로그에는 키 묶음 이름과 수만 남긴다")
  void logsCountsOnly() {
    Logger logger = (Logger) LoggerFactory.getLogger(KeyReencryptCommand.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    try {
      new KeyReencryptCommand(List.of(fixed("contact", 3, 0)), null, null).execute("contact");
    } finally {
      logger.detachAppender(appender);
    }

    assertThat(appender.list)
        .extracting(ILoggingEvent::getFormattedMessage)
        .containsExactly("key ring contact reencrypted: 3 values, 0 still on old versions");
  }

  private static KeyReencryptor fixed(String ring, long changed, long remaining) {
    return new KeyReencryptor() {
      @Override
      public String ring() {
        return ring;
      }

      @Override
      public Result reencrypt() {
        return new Result(changed, remaining);
      }
    };
  }
}
