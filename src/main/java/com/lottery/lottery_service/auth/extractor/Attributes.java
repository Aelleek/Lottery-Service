package com.lottery.lottery_service.auth.extractor;

import java.util.Map;

/** 제공자 속성 값을 문자열로 꺼내는 도우미. */
final class Attributes {

  private Attributes() {}

  /** 문자열이나 숫자면 문자열로, 비었거나 다른 타입이면 {@code null}. */
  static String string(Map<?, ?> attrs, String key) {
    Object value = attrs.get(key);
    if (value instanceof String s) {
      return s.isBlank() ? null : s;
    }
    if (value instanceof Number n) {
      return n.toString();
    }
    return null;
  }
}
