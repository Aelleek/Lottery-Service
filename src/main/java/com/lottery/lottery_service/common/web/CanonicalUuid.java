package com.lottery.lottery_service.common.web;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 요청으로 받은 UUID(외부 공개 ID, {@code Idempotency-Key})를 표준 36자 형식으로만 읽는다. {@link UUID#fromString}은
 * {@code 1-1-1-1-1}처럼 자리수가 모자란 값도 받아 openapi의 {@code format: uuid}보다 느슨하다.
 */
public final class CanonicalUuid {

  private static final Pattern FORMAT =
      Pattern.compile(
          "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

  private CanonicalUuid() {}

  /**
   * 표준 형식의 UUID를 읽는다.
   *
   * @throws IllegalArgumentException 형식이 다르거나 값이 없으면
   */
  public static UUID parse(String value) {
    if (value == null || !FORMAT.matcher(value).matches()) {
      throw new IllegalArgumentException("not a canonical uuid");
    }
    return UUID.fromString(value);
  }
}
