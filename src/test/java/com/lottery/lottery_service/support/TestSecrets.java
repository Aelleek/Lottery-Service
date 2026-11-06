package com.lottery.lottery_service.support;

import com.lottery.lottery_service.common.secret.SecretKeys;
import com.lottery.lottery_service.common.secret.SecretProperties;
import java.util.Base64;
import java.util.Map;

/** 테스트 전용 키. 운영 키가 아니다. */
public final class TestSecrets {

  private TestSecrets() {}

  /** 버전 1 키만 있는 키 설정. */
  public static SecretKeys keys() {
    return keys("command-token-test-key-0123456789", "guest-credential-test-key-012345");
  }

  /** 주어진 문자열을 키로 쓰는 설정. */
  public static SecretKeys keys(String commandToken, String guestCredential) {
    return new SecretKeys(
        new SecretProperties(
            new SecretProperties.Ring(1, Map.of(1, encode(commandToken))),
            new SecretProperties.Ring(1, Map.of(1, encode(guestCredential))),
            new SecretProperties.Ring(1, Map.of(1, encode("verification-code-test-key-01234"))),
            new SecretProperties.Ring(1, Map.of(1, encode("contact-cipher-test-key-012345678")))));
  }

  private static String encode(String value) {
    return Base64.getEncoder().encodeToString(value.getBytes());
  }
}
