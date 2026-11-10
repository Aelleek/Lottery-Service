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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 개발·테스트용 삭제 기록 저장소(D-052). 파일 끝에 한 줄씩 덧붙이고 디스크 기록을 확인({@code force})한 뒤에야 성공으로 본다. local·test
 * 프로필에서만 켜진다. 운영 저장소는 호스팅을 정할 때 고른다.
 *
 * <p>한 줄은 {@code {"scope":"MEMBER","memberPublicId":"…","requestedAt":"…"}}이다. 회원 외부 ID 밖의 개인 자료는
 * 넣지 않는다. 모르는 범위가 적힌 줄은 깨진 줄로 본다.
 */
@Component
@Profile({"local", "test"})
public class LocalFileDeletionJournal implements DeletionJournal, DeletionJournalReader {

  private static final Pattern LINE =
      Pattern.compile(
          "^\\{\"scope\":\"([A-Z_]+)\",\"memberPublicId\":\"([0-9a-f-]{36})\","
              + "\"requestedAt\":\"([^\"]+)\"\\}$");

  private final Path file;

  /** 저장할 파일로 만든다. 기본은 {@code ./build/deletion-journal/journal.jsonl}이다. */
  public LocalFileDeletionJournal(
      @Value("${lottery.deletion-journal.path:./build/deletion-journal/journal.jsonl}") Path file) {
    this.file = file;
  }

  @Override
  public synchronized void append(UUID memberPublicId, Instant requestedAt) {
    String line =
        "{\"scope\":\""
            + DeletionScope.MEMBER
            + "\",\"memberPublicId\":\""
            + memberPublicId
            + "\",\"requestedAt\":\""
            + requestedAt
            + "\"}\n";
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

  @Override
  public synchronized List<Entry> readAll() {
    if (!Files.exists(file)) {
      return List.of();
    }
    try {
      List<Entry> entries = new ArrayList<>();
      for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
        if (line.isBlank()) {
          continue;
        }
        Matcher m = LINE.matcher(line);
        if (!m.matches()) {
          throw new IllegalStateException("deletion journal line is invalid");
        }
        DeletionScope scope;
        try {
          scope = DeletionScope.valueOf(m.group(1));
        } catch (IllegalArgumentException e) {
          throw new IllegalStateException("deletion journal scope is unknown", e);
        }
        entries.add(new Entry(scope, UUID.fromString(m.group(2)), Instant.parse(m.group(3))));
      }
      return entries;
    } catch (IOException e) {
      throw new UncheckedIOException("deletion journal read failed", e);
    }
  }
}
