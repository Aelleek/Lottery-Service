package com.lottery.lottery_service.notification.verify;

import com.lottery.lottery_service.common.secret.KeyRing;
import com.lottery.lottery_service.common.secret.SecretKeys;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * 수신 번호를 연락처 암호화 키로 암호화한다(AES-256-GCM). 결과는 {@code [키 버전 4바이트][IV 12바이트][암호문+태그]}다. AES 키는 설정한 키에서
 * SHA-256으로 만든다.
 */
@Component
public class ContactCipher {

  private static final int IV_BYTES = 12;
  private static final int TAG_BITS = 128;

  private final SecretKeys secrets;
  private final SecureRandom random = new SecureRandom();

  /** 암호기를 만든다. */
  public ContactCipher(SecretKeys secrets) {
    this.secrets = secrets;
  }

  /** 현재 키 버전으로 암호화한다. */
  public byte[] encrypt(String plain) {
    KeyRing ring = secrets.contact();
    int version = ring.currentVersion();
    byte[] iv = new byte[IV_BYTES];
    random.nextBytes(iv);
    try {
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, aesKey(ring, version), new GCMParameterSpec(TAG_BITS, iv));
      byte[] body = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
      return ByteBuffer.allocate(4 + IV_BYTES + body.length)
          .putInt(version)
          .put(iv)
          .put(body)
          .array();
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  /** 암호문에 적힌 키 버전으로 복호화한다. 변조됐으면 예외. */
  public String decrypt(byte[] sealed) {
    ByteBuffer buffer = ByteBuffer.wrap(sealed);
    int version = buffer.getInt();
    byte[] iv = new byte[IV_BYTES];
    buffer.get(iv);
    byte[] body = new byte[buffer.remaining()];
    buffer.get(body);
    try {
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(
          Cipher.DECRYPT_MODE,
          aesKey(secrets.contact(), version),
          new GCMParameterSpec(TAG_BITS, iv));
      return new String(cipher.doFinal(body), StandardCharsets.UTF_8);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("contact ciphertext is invalid", e);
    }
  }

  private static SecretKeySpec aesKey(KeyRing ring, int version) throws GeneralSecurityException {
    byte[] digest = MessageDigest.getInstance("SHA-256").digest(ring.key(version));
    return new SecretKeySpec(digest, "AES");
  }
}
