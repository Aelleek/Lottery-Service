package com.lottery.lottery_service.common.secret;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/** 용도별 키 묶음을 준다. 설정이 없으면 앱 시작은 막지 않고 그 키를 처음 쓸 때 실패한다. */
@Component
@EnableConfigurationProperties(SecretProperties.class)
public class SecretKeys {

  private final SecretProperties properties;

  /** 설정으로 만든다. */
  public SecretKeys(SecretProperties properties) {
    this.properties = properties;
  }

  /** 명령 토큰 서명 키. */
  public KeyRing commandToken() {
    return ring(properties.commandToken(), "command-token");
  }

  /** 비회원 자격 해시 키. */
  public KeyRing guestCredential() {
    return ring(properties.guestCredential(), "guest-credential");
  }

  /** 인증 코드 HMAC 키. */
  public KeyRing verificationCode() {
    return ring(properties.verificationCode(), "verification-code");
  }

  /** 연락처 암호화 키. */
  public KeyRing contact() {
    return ring(properties.contact(), "contact");
  }

  private static KeyRing ring(SecretProperties.Ring ring, String name) {
    if (ring == null) {
      throw new IllegalStateException("secret key ring is not configured: " + name);
    }
    return ring.toKeyRing(name);
  }
}
