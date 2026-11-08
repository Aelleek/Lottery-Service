package com.lottery.lottery_service.common.secret;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 키 묶음으로 AES-256-GCM 암호화를 한다. 결과는 {@code [IV 12바이트][암호문+태그]}이고 키 버전은 따로 저장한다. AES 키는 설정한 키에서
 * SHA-256으로 만든다. 연결 데이터(AAD)로 저장 위치를 묶어 다른 행으로 옮긴 암호문은 풀리지 않게 한다.
 */
public final class VersionedCipher {

  private static final int IV_BYTES = 12;
  private static final int TAG_BITS = 128;
  private static final SecureRandom RANDOM = new SecureRandom();

  private VersionedCipher() {}

  /**
   * 암호화 결과.
   *
   * @param version 쓴 키 버전
   * @param sealed IV와 암호문
   */
  public record Sealed(int version, byte[] sealed) {}

  /** 현재 키 버전으로 암호화한다. */
  public static Sealed encrypt(KeyRing ring, byte[] plain, byte[] aad) {
    int version = ring.currentVersion();
    byte[] iv = new byte[IV_BYTES];
    RANDOM.nextBytes(iv);
    try {
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, aesKey(ring, version), new GCMParameterSpec(TAG_BITS, iv));
      cipher.updateAAD(aad);
      byte[] body = cipher.doFinal(plain);
      return new Sealed(
          version, ByteBuffer.allocate(IV_BYTES + body.length).put(iv).put(body).array());
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  /** 주어진 키 버전으로 복호화한다. 변조됐거나 연결 데이터가 다르면 예외. */
  public static byte[] decrypt(KeyRing ring, int version, byte[] sealed, byte[] aad) {
    ByteBuffer buffer = ByteBuffer.wrap(sealed);
    byte[] iv = new byte[IV_BYTES];
    buffer.get(iv);
    byte[] body = new byte[buffer.remaining()];
    buffer.get(body);
    try {
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.DECRYPT_MODE, aesKey(ring, version), new GCMParameterSpec(TAG_BITS, iv));
      cipher.updateAAD(aad);
      return cipher.doFinal(body);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("ciphertext is invalid", e);
    }
  }

  private static SecretKeySpec aesKey(KeyRing ring, int version) throws GeneralSecurityException {
    byte[] digest = MessageDigest.getInstance("SHA-256").digest(ring.key(version));
    return new SecretKeySpec(digest, "AES");
  }
}
