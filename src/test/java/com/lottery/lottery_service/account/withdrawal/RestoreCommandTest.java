package com.lottery.lottery_service.account.withdrawal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ConfigurableApplicationContext;

/** 복원 명령이 삭제 기록 저장소 유무와 읽기 실패에 따라 어떻게 끝나는지 확인한다(07 4절 3·4번, T30). */
class RestoreCommandTest {

  private final RestoreReplay replay = mock(RestoreReplay.class);
  private final DeletionJournalReader reader = mock(DeletionJournalReader.class);

  @SuppressWarnings("unchecked")
  private final ObjectProvider<DeletionJournalReader> provider = mock(ObjectProvider.class);

  private final RestoreCommand command =
      new RestoreCommand(replay, provider, mock(ConfigurableApplicationContext.class));

  @Test
  @DisplayName("삭제 기록 저장소가 없으면 아무것도 바꾸지 않고 종료 코드 2로 끝난다")
  void failsWithoutJournal() {
    when(provider.getIfAvailable()).thenReturn(null);

    assertThat(command.execute()).isEqualTo(2);
    verifyNoInteractions(replay);
  }

  @Test
  @DisplayName("삭제 기록을 다시 적용한 뒤 모든 세션을 회수하고 종료 코드 0으로 끝난다")
  void replaysThenRevokesSessions() {
    when(provider.getIfAvailable()).thenReturn(reader);
    when(replay.replay(reader)).thenReturn(new RestoreReplay.Result(1, 0, 0));

    assertThat(command.execute()).isZero();
    InOrder order = inOrder(replay);
    order.verify(replay).replay(reader);
    order.verify(replay).revokeAllSessions();
  }

  @Test
  @DisplayName("삭제 기록을 읽지 못하면 예외로 끝나고 세션 회수까지 가지 않는다")
  void stopsWhenJournalUnreadable() {
    when(provider.getIfAvailable()).thenReturn(reader);
    when(replay.replay(any())).thenThrow(new IllegalStateException("journal unreadable"));

    assertThatThrownBy(command::execute).isInstanceOf(IllegalStateException.class);
    verify(replay, never()).revokeAllSessions();
  }
}
