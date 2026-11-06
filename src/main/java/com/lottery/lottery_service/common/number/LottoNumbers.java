package com.lottery.lottery_service.common.number;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 서로 다른 번호 6개로 된 조합. 입력 순서와 상관없이 오름차순으로 보관하므로 순서만 다른 두 조합은 같다.
 *
 * <p>저장 형식은 {@link #key()}의 17자 문자열이다. 예: {@code 1, 2, 3, 4, 5, 6} → {@code "01 02 03 04 05 06"}.
 */
public final class LottoNumbers {

  /** 한 조합의 번호 개수. */
  public static final int SIZE = 6;

  /** {@link #key()} 문자열의 길이. */
  public static final int KEY_LENGTH = 17;

  private static final Pattern KEY_PATTERN = Pattern.compile("\\d{2}( \\d{2}){5}");

  private final List<LottoNumber> numbers;

  private LottoNumbers(List<LottoNumber> sorted) {
    this.numbers = List.copyOf(sorted);
  }

  /** 정수 목록으로 조합을 만든다. 순서는 상관없다. 개수·범위·중복이 틀리면 예외를 던진다. */
  public static LottoNumbers of(Collection<Integer> values) {
    if (values == null) {
      throw new InvalidLottoNumberException("번호 목록이 없습니다.");
    }
    if (values.size() != SIZE) {
      throw new InvalidLottoNumberException("번호는 정확히 " + SIZE + "개여야 합니다: " + values.size() + "개");
    }
    List<LottoNumber> result = new ArrayList<>(SIZE);
    Set<Integer> seen = new HashSet<>();
    for (Integer value : values) {
      if (value == null) {
        throw new InvalidLottoNumberException("번호에 빈 값이 있습니다.");
      }
      if (!seen.add(value)) {
        throw new InvalidLottoNumberException("번호가 중복됐습니다.");
      }
      result.add(LottoNumber.of(value));
    }
    result.sort(null);
    return new LottoNumbers(result);
  }

  /** 정수 여섯 개로 조합을 만든다. */
  public static LottoNumbers of(int... values) {
    if (values == null) {
      throw new InvalidLottoNumberException("번호 목록이 없습니다.");
    }
    List<Integer> boxed = new ArrayList<>(values.length);
    for (int value : values) {
      boxed.add(value);
    }
    return of(boxed);
  }

  /** {@link #key()} 형식의 문자열을 읽는다. 오름차순 두 자리 번호를 공백 하나로 구분한 형식만 받는다. */
  public static LottoNumbers fromKey(String key) {
    if (key == null || !KEY_PATTERN.matcher(key).matches()) {
      throw new InvalidLottoNumberException("번호 키 형식이 아닙니다.");
    }
    List<Integer> values = new ArrayList<>(SIZE);
    for (String part : key.split(" ")) {
      values.add(Integer.parseInt(part));
    }
    LottoNumbers parsed = of(values);
    if (!parsed.key().equals(key)) {
      throw new InvalidLottoNumberException("번호 키는 오름차순이어야 합니다.");
    }
    return parsed;
  }

  /** 저장과 유일성 비교에 쓰는 17자 정규 문자열. 예: {@code "01 02 03 04 05 06"}. */
  public String key() {
    StringBuilder sb = new StringBuilder(KEY_LENGTH);
    for (LottoNumber number : numbers) {
      if (sb.length() > 0) {
        sb.append(' ');
      }
      if (number.value() < 10) {
        sb.append('0');
      }
      sb.append(number.value());
    }
    return sb.toString();
  }

  /** 오름차순 정수 목록. API 응답의 정수 배열에 쓴다. */
  public List<Integer> toList() {
    return numbers.stream().map(LottoNumber::value).toList();
  }

  /** 오름차순 번호 목록. */
  public List<LottoNumber> numbers() {
    return numbers;
  }

  /** 이 조합에 번호가 들어 있는지. */
  public boolean contains(LottoNumber number) {
    return numbers.contains(number);
  }

  /** 다른 조합과 겹치는 번호 개수. */
  public int matchCount(LottoNumbers other) {
    int count = 0;
    for (LottoNumber number : numbers) {
      if (other.contains(number)) {
        count++;
      }
    }
    return count;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    return o instanceof LottoNumbers other && numbers.equals(other.numbers);
  }

  @Override
  public int hashCode() {
    return numbers.hashCode();
  }

  @Override
  public String toString() {
    return key();
  }
}
