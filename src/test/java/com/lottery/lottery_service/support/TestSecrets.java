package com.lottery.lottery_service.support;

import com.lottery.lottery_service.common.secret.SecretKeys;
import com.lottery.lottery_service.common.secret.SecretProperties;
import java.util.Base64;
import java.util.Map;

/** 테스트 전용 키. 운영 키가 아니다. */
public final class TestSecrets {

  private static final String CONTACT_V1 = "contact-cipher-test-key-012345678";
  private static final String CONTACT_V2 = "contact-cipher-test-key-version-2";
  private static final String ADMIN_MFA_V1 = "admin-mfa-test-key-0123456789abc";
  private static final String ADMIN_MFA_V2 = "admin-mfa-test-key-version-2-xyz";

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
            new SecretProperties.Ring(1, Map.of(1, encode(CONTACT_V1))),
            new SecretProperties.Ring(1, Map.of(1, encode("csrf-token-test-key-0123456789ab"))),
            new SecretProperties.Ring(1, Map.of(1, encode(ADMIN_MFA_V1)))));
  }

  /**
   * 연락처·관리자 인증 앱 암호화 키에 버전 2를 더하고 현재 버전으로 지정한 설정. 버전 1은 {@link #keys()}와 같다. 다른 키는 {@link #keys()}와
   * 같다.
   */
  public static SecretKeys rotated() {
    return encryptionKeys(
        2,
        Map.of(1, encode(CONTACT_V1), 2, encode(CONTACT_V2)),
        Map.of(1, encode(ADMIN_MFA_V1), 2, encode(ADMIN_MFA_V2)));
  }

  /** {@link #rotated()}에서 버전 1을 지운 설정. 다시 암호화하지 않은 값은 풀리지 않는다. */
  public static SecretKeys rotatedWithoutOld() {
    return encryptionKeys(2, Map.of(2, encode(CONTACT_V2)), Map.of(2, encode(ADMIN_MFA_V2)));
  }

  private static SecretKeys encryptionKeys(
      int current, Map<Integer, String> contact, Map<Integer, String> adminMfa) {
    return new SecretKeys(
        new SecretProperties(
            new SecretProperties.Ring(1, Map.of(1, encode("command-token-test-key-0123456789"))),
            new SecretProperties.Ring(1, Map.of(1, encode("guest-credential-test-key-012345"))),
            new SecretProperties.Ring(1, Map.of(1, encode("verification-code-test-key-01234"))),
            new SecretProperties.Ring(current, contact),
            new SecretProperties.Ring(1, Map.of(1, encode("csrf-token-test-key-0123456789ab"))),
            new SecretProperties.Ring(current, adminMfa)));
  }

  private static String encode(String value) {
    return Base64.getEncoder().encodeToString(value.getBytes());
  }
}
