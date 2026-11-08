package com.lottery.lottery_service.account.withdrawal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalFileDeletionJournalTest {

  @TempDir Path dir;

  @Test
  @DisplayName("한 줄씩 덧붙이고 앞 기록을 지우지 않는다. 회원 외부 ID와 요청 시각만 남긴다")
  void appendsLines() throws Exception {
    Path file = dir.resolve("nested/journal.jsonl");
    LocalFileDeletionJournal journal = new LocalFileDeletionJournal(file);
    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID();

    journal.append(a, Instant.parse("2026-11-08T15:00:00Z"));
    journal.append(b, Instant.parse("2026-11-08T15:01:00Z"));

    assertThat(Files.readAllLines(file))
        .containsExactly(
            "{\"memberPublicId\":\"" + a + "\",\"requestedAt\":\"2026-11-08T15:00:00Z\"}",
            "{\"memberPublicId\":\"" + b + "\",\"requestedAt\":\"2026-11-08T15:01:00Z\"}");
  }

  @Test
  @DisplayName("저장하지 못하면 예외라 삭제 job이 다음으로 넘어가지 않는다")
  void failsWhenUnwritable() throws Exception {
    Path blocker = dir.resolve("file");
    Files.writeString(blocker, "x");
    LocalFileDeletionJournal journal =
        new LocalFileDeletionJournal(blocker.resolve("journal.jsonl"));

    assertThatThrownBy(() -> journal.append(UUID.randomUUID(), Instant.EPOCH))
        .isInstanceOf(UncheckedIOException.class);
  }
}
