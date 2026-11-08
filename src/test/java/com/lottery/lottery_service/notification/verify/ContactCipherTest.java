package com.lottery.lottery_service.notification.verify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lottery.lottery_service.common.secret.SecretKeys;
import com.lottery.lottery_service.common.secret.SecretProperties;
import com.lottery.lottery_service.common.secret.SecretProperties.Ring;
import com.lottery.lottery_service.support.TestSecrets;
import java.nio.ByteBuffer;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ContactCipherTest {

  private final ContactCipher cipher = new ContactCipher(TestSecrets.keys());

  @Test
  @DisplayName("암호화한 번호는 그대로 복호화되고, 같은 번호도 매번 다른 암호문이 된다")
  void roundTrip() {
    byte[] a = cipher.encrypt(7L, "01012345678");
    byte[] b = cipher.encrypt(7L, "01012345678");

    assertThat(cipher.decrypt(7L, a)).isEqualTo("01012345678");
    assertThat(a).isNotEqualTo(b);
    assertThat(new String(a)).doesNotContain("01012345678");
  }

  @Test
  @DisplayName("암호문이 한 바이트라도 바뀌면 복호화를 거부한다")
  void tamperRejected() {
    byte[] sealed = cipher.encrypt(7L, "01012345678");
    sealed[sealed.length - 1] ^= 1;

    assertThatThrownBy(() -> cipher.decrypt(7L, sealed)).isInstanceOf(IllegalStateException.class);
  }

  @Test
  @DisplayName("다른 회원의 행으로 옮긴 암호문은 풀리지 않는다")
  void boundToMember() {
    byte[] sealed = cipher.encrypt(7L, "01012345678");

    assertThatThrownBy(() -> cipher.decrypt(8L, sealed)).isInstanceOf(IllegalStateException.class);
  }

  @Test
  @DisplayName("키를 바꾼 뒤에도 예전 버전 암호문을 읽고, 새 암호문은 새 버전으로 쓴다")
  void rotation() {
    byte[] old = cipher.encrypt(7L, "01012345678");
    ContactCipher rotated = new ContactCipher(contactKeys(2));

    assertThat(rotated.decrypt(7L, old)).isEqualTo("01012345678");
    assertThat(ByteBuffer.wrap(rotated.encrypt(7L, "01099998888")).getInt()).isEqualTo(2);
  }

  private static SecretKeys contactKeys(int current) {
    Ring other = new Ring(1, Map.of(1, encode("other-test-key-0123456789abcdefg")));
    Ring contact =
        new Ring(
            current,
            Map.of(
                1, encode("contact-cipher-test-key-012345678"),
                2, encode("contact-cipher-test-key-rotated-2")));
    return new SecretKeys(new SecretProperties(other, other, other, contact, other, other));
  }

  private static String encode(String value) {
    return Base64.getEncoder().encodeToString(value.getBytes());
  }
}
