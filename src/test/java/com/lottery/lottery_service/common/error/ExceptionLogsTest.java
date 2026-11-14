package com.lottery.lottery_service.common.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.sql.SQLException;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.Logger;
import org.springframework.dao.DuplicateKeyException;

class ExceptionLogsTest {

  @Test
  @DisplayName("DB 예외는 종류와 SQL 상태만 남기고 메시지·원인 체인(번호 조합)은 남기지 않는다(03 6.1절)")
  void dbErrorKeepsOnlyTypeAndState() {
    Logger log = mock(Logger.class);
    DuplicateKeyException e =
        new DuplicateKeyException(
            "INSERT ... '01 02 03 04 05 06'",
            new SQLException("Duplicate entry '7-1200-01 02 03 04 05 06'", "23000", 1062));

    ExceptionLogs.warn(log, "job failed id={} type={}", e, 7L, "HISTORY_IMPORT");

    ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
    verify(log)
        .warn(eq("job failed id={} type={} dbError={} sqlState={} errorCode={}"), args.capture());
    assertThat(args.getValue())
        .containsExactly(7L, "HISTORY_IMPORT", "DuplicateKeyException", "23000", 1062);
    assertThat(Arrays.toString(args.getValue())).doesNotContain("01 02");
  }

  @Test
  @DisplayName("DB 예외가 아니면 원인과 함께 남긴다")
  void otherErrorKeepsCause() {
    Logger log = mock(Logger.class);
    IllegalStateException e = new IllegalStateException("boom");

    ExceptionLogs.warn(log, "job failed id={}", e, 7L);

    ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
    verify(log).warn(anyString(), args.capture());
    assertThat(args.getValue()).containsExactly(7L, e);
  }
}
