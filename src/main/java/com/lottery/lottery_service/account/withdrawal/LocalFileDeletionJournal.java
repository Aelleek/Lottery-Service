package com.lottery.lottery_service.account.withdrawal;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 개발·테스트용 삭제 기록 저장소(D-052). 파일 끝에 한 줄씩 덧붙이고 디스크 기록을 확인({@code force})한 뒤에야 성공으로 본다. local·test
 * 프로필에서만 켜진다. 운영 저장소는 호스팅을 정할 때 고른다.
 *
 * <p>한 줄은 {@code {"memberPublicId":"…","requestedAt":"…"}}이다. 회원 외부 ID 밖의 개인 자료는 넣지 않는다.
 */
@Component
@Profile({"local", "test"})
public class LocalFileDeletionJournal implements DeletionJournal {

  private final Path file;

  /** 저장할 파일로 만든다. 기본은 {@code ./build/deletion-journal/journal.jsonl}이다. */
  public LocalFileDeletionJournal(
      @Value("${lottery.deletion-journal.path:./build/deletion-journal/journal.jsonl}") Path file) {
    this.file = file;
  }

  @Override
  public synchronized void append(UUID memberPublicId, Instant requestedAt) {
    String line =
        "{\"memberPublicId\":\"" + memberPublicId + "\",\"requestedAt\":\"" + requestedAt + "\"}\n";
    try {
      Path parent = file.toAbsolutePath().getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
      try (FileChannel channel =
          FileChannel.open(
              file,
              StandardOpenOption.CREATE,
              StandardOpenOption.WRITE,
              StandardOpenOption.APPEND)) {
        ByteBuffer buffer = ByteBuffer.wrap(line.getBytes(StandardCharsets.UTF_8));
        while (buffer.hasRemaining()) {
          channel.write(buffer);
        }
        channel.force(true);
      }
    } catch (IOException e) {
      throw new UncheckedIOException("deletion journal append failed", e);
    }
  }
}
