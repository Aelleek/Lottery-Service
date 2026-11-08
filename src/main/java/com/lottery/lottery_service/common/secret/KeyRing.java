package com.lottery.lottery_service.common.secret;

import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** 버전이 있는 HMAC-SHA256 키 묶음. 새로 만드는 값은 현재 버전으로, 확인은 모든 버전으로 한다(키 교체 중에도 옛 값이 맞게). */
public final class KeyRing {

  private static final String ALGORITHM = "HmacSHA256";
  private static final int MIN_KEY_BYTES = 32;

  private final int current;
  private final Map<Integer, byte[]> keys;

  /**
   * 키 묶음을 만든다.
   *
   * @param current 새 값에 쓸 버전
   * @param keys 버전별 키. 각각 32바이트 이상
   */
  public KeyRing(int current, Map<Integer, byte[]> keys) {
    if (!keys.containsKey(current)) {
      throw new IllegalArgumentException("current key version is missing");
    }
    keys.values()
        .forEach(
            k -> {
              if (k.length < MIN_KEY_BYTES) {
                throw new IllegalArgumentException("secret keys must be at least 32 bytes");
              }
            });
    this.current = current;
    this.keys = new TreeMap<>();
    keys.forEach((v, k) -> this.keys.put(v, k.clone()));
  }

  /** 현재 버전 키로 계산한 HMAC. */
  public byte[] sign(byte[] message) {
    return mac(keys.get(current), message);
  }

  /** 현재 버전. */
  public int currentVersion() {
    return current;
  }

  /** 주어진 버전의 키 원본 사본. 그 버전이 없으면 예외. 암호화 키를 만들 때만 쓴다. */
  public byte[] key(int version) {
    byte[] key = keys.get(version);
    if (key == null) {
      throw new IllegalArgumentException("unknown key version");
    }
    return key.clone();
  }

  /** 주어진 버전 키로 계산한 HMAC. 그 버전이 없으면 예외. */
  public byte[] sign(int version, byte[] message) {
    byte[] key = keys.get(version);
    if (key == null) {
      throw new IllegalArgumentException("unknown key version");
    }
    return mac(key, message);
  }

  /** 모든 버전 키로 계산한 HMAC. 현재 버전이 먼저다. */
  public List<byte[]> signWithAll(byte[] message) {
    List<byte[]> result = new ArrayList<>();
    result.add(sign(message));
    keys.forEach(
        (v, k) -> {
          if (v != current) {
            result.add(mac(k, message));
          }
        });
    return result;
  }

  private static byte[] mac(byte[] key, byte[] message) {
    try {
      Mac mac = Mac.getInstance(ALGORITHM);
      mac.init(new SecretKeySpec(key, ALGORITHM));
      return mac.doFinal(message);
    } catch (NoSuchAlgorithmException | InvalidKeyException e) {
      throw new IllegalStateException(e);
    }
  }
}
