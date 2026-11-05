package com.lottery.lottery_service.common.number;

/** 로또 번호 하나(1~45). */
public record LottoNumber(int value) implements Comparable<LottoNumber> {

  /** 가장 작은 번호. */
  public static final int MIN = 1;

  /** 가장 큰 번호. */
  public static final int MAX = 45;

  /** 범위를 벗어나면 {@link InvalidLottoNumberException}을 던진다. */
  public LottoNumber {
    if (value < MIN || value > MAX) {
      throw new InvalidLottoNumberException("번호는 " + MIN + "~" + MAX + " 사이여야 합니다.");
    }
  }

  /** 정수로 번호를 만든다. */
  public static LottoNumber of(int value) {
    return new LottoNumber(value);
  }

  @Override
  public int compareTo(LottoNumber other) {
    return Integer.compare(value, other.value);
  }

  @Override
  public String toString() {
    return Integer.toString(value);
  }
}
