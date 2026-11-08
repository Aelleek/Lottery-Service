package com.lottery.lottery_service.common.secret;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class KeyRingTest {

  private static final byte[] K1 = "k1-0123456789-0123456789-0123456".getBytes();
  private static final byte[] K2 = "k2-0123456789-0123456789-0123456".getBytes();

  @Test
  @DisplayName("현재 버전으로 서명하고, 확인용 목록은 현재 버전이 먼저다")
  void signsWithCurrentFirst() {
    KeyRing ring = new KeyRing(2, Map.of(1, K1, 2, K2));
    byte[] message = "token".getBytes();

    assertThat(ring.sign(message))
        .isEqualTo(ring.sign(2, message))
        .isNotEqualTo(ring.sign(1, message));
    assertThat(ring.signWithAll(message)).hasSize(2);
    assertThat(ring.signWithAll(message).get(0)).isEqualTo(ring.sign(message));
  }

  @Test
  @DisplayName("현재 버전이 없거나 키가 32바이트보다 짧으면 만들지 않는다")
  void rejectsInvalidRing() {
    assertThatThrownBy(() -> new KeyRing(3, Map.of(1, K1)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new KeyRing(1, Map.of(1, "short".getBytes())))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("설정이 없으면 그 키를 쓸 때 실패한다")
  void missingConfigurationFailsOnUse() {
    SecretKeys keys = new SecretKeys(new SecretProperties(null, null, null));

    assertThatThrownBy(keys::guestCredential).isInstanceOf(IllegalStateException.class);
  }
}
