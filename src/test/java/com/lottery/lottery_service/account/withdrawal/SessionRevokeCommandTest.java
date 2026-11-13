package com.lottery.lottery_service.account.withdrawal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;

/** 사고 대응 세션 회수 명령(07 7절 2번). 회수 자체는 {@code WithdrawalMySqlTest}가 MySQL에서 확인한다. */
class SessionRevokeCommandTest {

  @Test
  @DisplayName("모든 회원 세션을 회수하고 삭제 기록 재적용은 하지 않는다")
  void revokesOnly() {
    RestoreReplay replay = mock(RestoreReplay.class);
    when(replay.revokeAllSessions()).thenReturn(3);

    assertThat(
            new SessionRevokeCommand(replay, mock(ConfigurableApplicationContext.class)).execute())
        .isZero();
    verify(replay).revokeAllSessions();
    verifyNoMoreInteractions(replay);
  }
}
