package com.lottery.lottery_service.common.secret;

/**
 * 한 암호화 키 묶음으로 저장한 값을 현재 버전으로 다시 암호화한다(03 6.6, C-30). 옛 버전 키를 지우기 전에 {@link KeyReencryptCommand}로
 * 부른다.
 */
public interface KeyReencryptor {

  /** 다루는 키 묶음 이름. 설정 이름과 같다(예: {@code contact}). */
  String ring();

  /** 현재 버전이 아닌 값을 모두 다시 암호화하고, 끝난 뒤에도 옛 버전으로 남은 값의 수를 센다. */
  Result reencrypt();

  /**
   * 다시 암호화한 결과.
   *
   * @param reencrypted 이번에 다시 암호화한 값의 수
   * @param remaining 끝난 뒤에도 현재 버전이 아닌 값의 수. 0이어야 옛 버전 키를 지울 수 있다
   */
  record Result(long reencrypted, long remaining) {

    /** 두 결과를 더한다. */
    public Result plus(Result other) {
      return new Result(reencrypted + other.reencrypted, remaining + other.remaining);
    }
  }
}
