package com.lottery.lottery_service.recommendation.generator;

import com.lottery.lottery_service.common.error.BusinessException;
import com.lottery.lottery_service.common.error.ErrorCode;
import com.lottery.lottery_service.common.number.LottoNumber;
import java.util.Collection;
import java.util.Set;
import java.util.TreeSet;

/**
 * 조건 추천의 넣을 번호와 뺄 번호(02 2.3절). 둘 다 비면 기본 추천이다.
 *
 * @param include 반드시 넣을 번호. 0~5개
 * @param exclude 뺄 번호. 0~39개
 */
public record RecommendationCondition(Set<Integer> include, Set<Integer> exclude) {

  /** 넣을 번호 상한. */
  public static final int MAX_INCLUDE = 5;

  /** 뺄 번호 상한. */
  public static final int MAX_EXCLUDE = 39;

  private static final RecommendationCondition NONE =
      new RecommendationCondition(Set.of(), Set.of());

  /** 범위와 개수, 겹침을 확인하고 정렬한 사본으로 둔다. */
  public RecommendationCondition {
    include = Set.copyOf(new TreeSet<>(include));
    exclude = Set.copyOf(new TreeSet<>(exclude));
  }

  /** 기본 추천(조건 없음). */
  public static RecommendationCondition none() {
    return NONE;
  }

  /**
   * 입력 목록으로 조건을 만든다. 중복 입력, 범위 밖 숫자, 개수 초과, 양쪽에 같은 숫자가 있으면 거부한다.
   *
   * @throws BusinessException 규칙을 어기면 {@code CONDITION_INVALID}
   */
  public static RecommendationCondition of(
      Collection<Integer> include, Collection<Integer> exclude) {
    Set<Integer> in = distinctInRange(include);
    Set<Integer> out = distinctInRange(exclude);
    if (in.size() > MAX_INCLUDE || out.size() > MAX_EXCLUDE) {
      throw invalid();
    }
    for (int n : in) {
      if (out.contains(n)) {
        throw invalid();
      }
    }
    return new RecommendationCondition(in, out);
  }

  /** 조건이 없는 기본 추천인지. */
  public boolean isDefault() {
    return include.isEmpty() && exclude.isEmpty();
  }

  /** 조건으로 남는 후보 조합 수. C(45 − 넣을 수 − 뺄 수, 6 − 넣을 수). */
  public long candidateCount() {
    return binomial(LottoNumber.MAX - include.size() - exclude.size(), 6 - include.size());
  }

  static long binomial(int n, int k) {
    if (k < 0 || n < k) {
      return 0;
    }
    long result = 1;
    for (int i = 1; i <= k; i++) {
      result = result * (n - k + i) / i;
    }
    return result;
  }

  private static Set<Integer> distinctInRange(Collection<Integer> values) {
    Set<Integer> set = new TreeSet<>();
    if (values == null) {
      return set;
    }
    for (Integer v : values) {
      if (v == null || v < LottoNumber.MIN || v > LottoNumber.MAX || !set.add(v)) {
        throw invalid();
      }
    }
    return set;
  }

  private static BusinessException invalid() {
    return new BusinessException(ErrorCode.CONDITION_INVALID, "넣을 번호와 뺄 번호를 확인해 주세요.");
  }
}
