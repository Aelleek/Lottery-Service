package com.lottery.lottery_service.common.db;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/** JDBC로 새 스키마를 다룰 때 쓰는 값 변환. 시각은 UTC {@code DATETIME(6)}, 외부 ID는 {@code BINARY(16)}이다. */
public final class DbValues {

  private DbValues() {}

  /** 시각을 UTC 기준 {@link LocalDateTime}으로 바꾼다. 드라이버의 시간대 변환을 거치지 않게 하려는 것이다. */
  public static LocalDateTime utc(Instant instant) {
    return instant == null ? null : LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
  }

  /** UTC 기준 {@link LocalDateTime}을 시각으로 바꾼다. */
  public static Instant instant(LocalDateTime value) {
    return value == null ? null : value.toInstant(ZoneOffset.UTC);
  }

  /** UUID를 16바이트로 바꾼다. */
  public static byte[] bytes(UUID id) {
    return ByteBuffer.allocate(16)
        .putLong(id.getMostSignificantBits())
        .putLong(id.getLeastSignificantBits())
        .array();
  }

  /** 16바이트를 UUID로 바꾼다. {@code null}이면 {@code null}. */
  public static UUID uuid(byte[] value) {
    if (value == null) {
      return null;
    }
    ByteBuffer b = ByteBuffer.wrap(value);
    return new UUID(b.getLong(), b.getLong());
  }
}
