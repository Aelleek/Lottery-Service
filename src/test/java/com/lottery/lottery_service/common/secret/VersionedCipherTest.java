package com.lottery.lottery_service.common.secret;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class VersionedCipherTest {

  private static final byte[] K1 =
      "admin-mfa-test-key-0123456789abc".getBytes(StandardCharsets.UTF_8);
  private static final byte[] K2 =
      "admin-mfa-next-key-0123456789xyz".getBytes(StandardCharsets.UTF_8);
  private static final byte[] AAD = "admin-mfa|7".getBytes(StandardCharsets.UTF_8);
  private static final byte[] PLAIN = "secret-bytes".getBytes(StandardCharsets.UTF_8);

  @Test
  @DisplayName("같은 키 버전과 연결 데이터로만 풀리고, 같은 평문도 매번 다른 암호문이다")
  void roundTrip() {
    KeyRing ring = new KeyRing(1, Map.of(1, K1));
    VersionedCipher.Sealed a = VersionedCipher.encrypt(ring, PLAIN, AAD);
    VersionedCipher.Sealed b = VersionedCipher.encrypt(ring, PLAIN, AAD);

    assertThat(a.version()).isEqualTo(1);
    assertThat(a.sealed()).isNotEqualTo(b.sealed());
    assertThat(VersionedCipher.decrypt(ring, 1, a.sealed(), AAD)).isEqualTo(PLAIN);
  }

  @Test
  @DisplayName("다른 행의 연결 데이터, 바뀐 암호문, 모르는 키 버전은 거부한다")
  void rejectsMisuse() {
    KeyRing ring = new KeyRing(1, Map.of(1, K1));
    byte[] sealed = VersionedCipher.encrypt(ring, PLAIN, AAD).sealed();
    byte[] tampered = sealed.clone();
    tampered[tampered.length - 1] ^= 1;

    assertThatThrownBy(
            () ->
                VersionedCipher.decrypt(
                    ring, 1, sealed, "admin-mfa|8".getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> VersionedCipher.decrypt(ring, 1, tampered, AAD))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> VersionedCipher.decrypt(ring, 2, sealed, AAD))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("키를 새 버전으로 바꿔도 옛 버전 암호문은 남은 옛 키로 풀리고, 새 값은 새 버전으로 만든다(C-30)")
  void rotation() {
    byte[] old = VersionedCipher.encrypt(new KeyRing(1, Map.of(1, K1)), PLAIN, AAD).sealed();
    KeyRing rotated = new KeyRing(2, Map.of(1, K1, 2, K2));

    assertThat(VersionedCipher.decrypt(rotated, 1, old, AAD)).isEqualTo(PLAIN);
    VersionedCipher.Sealed fresh = VersionedCipher.encrypt(rotated, PLAIN, AAD);
    assertThat(fresh.version()).isEqualTo(2);
    assertThatThrownBy(() -> VersionedCipher.decrypt(rotated, 1, fresh.sealed(), AAD))
        .isInstanceOf(IllegalStateException.class);
  }
}
