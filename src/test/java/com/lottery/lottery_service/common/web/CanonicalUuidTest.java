package com.lottery.lottery_service.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CanonicalUuidTest {

  @Test
  @DisplayName("표준 36자 UUID는 대소문자와 상관없이 읽는다")
  void parsesCanonical() {
    UUID id = UUID.randomUUID();
    assertThat(CanonicalUuid.parse(id.toString())).isEqualTo(id);
    assertThat(CanonicalUuid.parse(id.toString().toUpperCase(Locale.ROOT))).isEqualTo(id);
  }

  @Test
  @DisplayName("UUID.fromString이 받는 짧은 형식, 앞뒤 공백, 빈 값, null은 거절한다")
  void rejectsLenientForms() {
    String id = UUID.randomUUID().toString();
    for (String value : new String[] {"1-1-1-1-1", id.substring(1), " " + id, id + "0", "", null}) {
      assertThatThrownBy(() -> CanonicalUuid.parse(value))
          .as(String.valueOf(value))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }
}
