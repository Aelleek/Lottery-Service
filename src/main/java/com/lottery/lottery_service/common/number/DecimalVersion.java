package com.lottery.lottery_service.common.number;

import java.util.regex.Pattern;

/** 요청의 버전 문자열(openapi {@code ^[0-9]+$})을 읽는다. 부호, 공백, ASCII가 아닌 숫자는 받지 않는다. */
public final class DecimalVersion {

  private static final Pattern DIGITS = Pattern.compile("[0-9]+");

  private DecimalVersion() {}

  /**
   * 십진 문자열 버전을 읽는다.
   *
   * @throws NumberFormatException 형식이 틀리거나 {@code long} 범위를 넘으면
   */
  public static long parse(String value) {
    if (value == null || !DIGITS.matcher(value).matches()) {
      throw new NumberFormatException("version must be ascii digits");
    }
    return Long.parseLong(value);
  }
}
