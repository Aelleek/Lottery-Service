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
  @DisplayName("한 줄씩 덧붙이고 앞 기록을 지우지 않는다. 범위, 회원 외부 ID, 요청 시각만 남긴다")
  void appendsLines() throws Exception {
    Path file = dir.resolve("nested/journal.jsonl");
    LocalFileDeletionJournal journal = new LocalFileDeletionJournal(file);
    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID();

    journal.append(a, Instant.parse("2026-11-08T15:00:00Z"));
    journal.append(b, Instant.parse("2026-11-08T15:01:00Z"));

    assertThat(Files.readAllLines(file))
        .containsExactly(
            "{\"scope\":\"MEMBER\",\"memberPublicId\":\""
                + a
                + "\",\"requestedAt\":\"2026-11-08T15:00:00Z\"}",
            "{\"scope\":\"MEMBER\",\"memberPublicId\":\""
                + b
                + "\",\"requestedAt\":\"2026-11-08T15:01:00Z\"}");
  }

  @Test
  @DisplayName("남은 기록을 순서대로 읽고, 파일이 없으면 빈 목록, 깨진 줄·범위 없는 줄·모르는 범위는 예외로 멈춘다")
  void readsBack() throws Exception {
    Path file = dir.resolve("journal.jsonl");
    LocalFileDeletionJournal journal = new LocalFileDeletionJournal(file);
    assertThat(journal.readAll()).isEmpty();
    UUID a = UUID.randomUUID();
    journal.append(a, Instant.parse("2026-11-08T15:00:00Z"));

    assertThat(journal.readAll())
        .containsExactly(
            new DeletionJournalReader.Entry(
                DeletionScope.MEMBER, a, Instant.parse("2026-11-08T15:00:00Z")));
    Files.writeString(
        file,
        "{\"scope\":\"ROUND\",\"memberPublicId\":\""
            + a
            + "\",\"requestedAt\":\"2026-11-08T15:00:00Z\"}\n",
        java.nio.file.StandardOpenOption.APPEND);
    assertThatThrownBy(journal::readAll).isInstanceOf(IllegalStateException.class);
    Files.writeString(file, "");
    Files.writeString(
        file, "{\"memberPublicId\":\"" + a + "\",\"requestedAt\":\"2026-11-08T15:00:00Z\"}\n");
    assertThatThrownBy(journal::readAll).isInstanceOf(IllegalStateException.class);
    Files.writeString(file, "garbage\n");
    assertThatThrownBy(journal::readAll).isInstanceOf(IllegalStateException.class);
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
