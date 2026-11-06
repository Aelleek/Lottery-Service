package com.lottery.lottery_service.common.secret;

import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code config/secrets.yml}의 키 설정({@code lottery.secrets.*}). 키 값은 Base64이고 git에 올리지 않는다.
 *
 * @param commandToken 명령 토큰 서명 키
 * @param guestCredential 비회원 자격 해시 키
 */
@ConfigurationProperties(prefix = "lottery.secrets")
public record SecretProperties(Ring commandToken, Ring guestCredential) {

  /**
   * 한 용도의 키 설정.
   *
   * @param current 새 값에 쓸 버전
   * @param keys 버전별 Base64 키
   */
  public record Ring(Integer current, Map<Integer, String> keys) {

    /** 키 묶음으로 바꾼다. */
    KeyRing toKeyRing(String name) {
      if (current == null || keys == null || keys.isEmpty()) {
        throw new IllegalStateException("secret key ring is not configured: " + name);
      }
      Map<Integer, byte[]> decoded = new HashMap<>();
      keys.forEach((v, k) -> decoded.put(v, Base64.getDecoder().decode(k)));
      return new KeyRing(current, decoded);
    }
  }
}
