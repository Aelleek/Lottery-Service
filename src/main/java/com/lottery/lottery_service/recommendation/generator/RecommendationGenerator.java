package com.lottery.lottery_service.recommendation.generator;

import com.lottery.lottery_service.common.number.LottoNumber;
import com.lottery.lottery_service.common.number.LottoNumbers;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.random.RandomGenerator;
import java.util.stream.IntStream;

/**
 * 추천 5세트를 만든다(02 2.2절, 05 2절 5번). 회원과 비회원이 같은 생성기를 쓴다.
 *
 * <ul>
 *   <li>기본 추천: 1~45에서 6개를 편향 없이 뽑고, 생성 필터와 이력 중복 검사를 통과한 것만 쓴다.
 *   <li>조건 추천: 넣을 번호를 고정하고 남은 숫자에서 채운 뒤 이력 중복만 검사한다. 후보가 10만 개 이하면 전부 나열해 남은 조합에서 고른다.
 *   <li>1,000번 뽑거나 250ms가 지나면 멈추고 계산 지연으로 본다.
 * </ul>
 *
 * <p>트랜잭션 밖에서 부른다. 상태를 갖지 않으며 난수와 시계는 주입한다.
 */
public final class RecommendationGenerator {

  /** 추천 1회의 세트 수. */
  public static final int SETS = 5;

  /** 한 요청에서 뽑는 횟수 상한. */
  public static final int MAX_ATTEMPTS = 1_000;

  /** 한 요청의 계산 시간 상한. */
  public static final Duration TIME_LIMIT = Duration.ofMillis(250);

  /** 전부 나열하는 조건 추천 후보 수 상한. */
  public static final long ENUMERATION_LIMIT = 100_000;

  private static final int TIME_CHECK_INTERVAL = 1_024;

  private final RandomGenerator random;
  private final LongSupplier nanoTime;

  /** 난수원과 경과 시간 측정용 시계({@code System::nanoTime} 같은 것)로 만든다. */
  public RecommendationGenerator(RandomGenerator random, LongSupplier nanoTime) {
    this.random = random;
    this.nanoTime = nanoTime;
  }

  /**
   * 5세트를 만든다.
   *
   * @param condition 조건. 기본 추천이면 {@link RecommendationCondition#none()}
   * @param filter 기본 추천의 생성 필터. 조건 추천에서는 무시한다
   * @param seen 이 주체가 이 회차에 이미 받은 조합(귀속한 비회원 이력 포함)
   */
  public GenerationOutcome generate(
      RecommendationCondition condition, CandidateFilter filter, Set<LottoNumbers> seen) {
    long start = nanoTime.getAsLong();
    if (condition.isDefault()) {
      return sample(condition, filter, seen, start);
    }
    long count = condition.candidateCount();
    if (count < SETS) {
      return new GenerationOutcome.Exhausted(count);
    }
    if (count <= ENUMERATION_LIMIT) {
      return enumerate(condition, seen, start);
    }
    return sample(condition, CandidateFilter.NONE, seen, start);
  }

  private GenerationOutcome sample(
      RecommendationCondition condition,
      CandidateFilter filter,
      Set<LottoNumbers> seen,
      long start) {
    int[] pool = pool(condition);
    int pick = LottoNumbers.SIZE - condition.include().size();
    Set<LottoNumbers> chosen = new LinkedHashSet<>();
    for (int attempt = 0; attempt < MAX_ATTEMPTS && chosen.size() < SETS; attempt++) {
      if (expired(start)) {
        return new GenerationOutcome.TimedOut();
      }
      LottoNumbers candidate = draw(pool, pick, condition.include());
      if (!seen.contains(candidate) && !chosen.contains(candidate) && filter.accepts(candidate)) {
        chosen.add(candidate);
      }
    }
    if (chosen.size() < SETS) {
      return new GenerationOutcome.TimedOut();
    }
    return new GenerationOutcome.Generated(new ArrayList<>(chosen));
  }

  private GenerationOutcome enumerate(
      RecommendationCondition condition, Set<LottoNumbers> seen, long start) {
    int[] pool = pool(condition);
    int pick = LottoNumbers.SIZE - condition.include().size();
    List<LottoNumbers> remaining = new ArrayList<>();
    int[] index = new int[pick];
    for (int i = 0; i < pick; i++) {
      index[i] = i;
    }
    long visited = 0;
    while (true) {
      if (++visited % TIME_CHECK_INTERVAL == 0 && expired(start)) {
        return new GenerationOutcome.TimedOut();
      }
      LottoNumbers candidate = combine(pool, index, condition.include());
      if (!seen.contains(candidate)) {
        remaining.add(candidate);
      }
      if (!next(index, pool.length)) {
        break;
      }
    }
    if (remaining.size() < SETS) {
      return new GenerationOutcome.Exhausted(remaining.size());
    }
    for (int i = 0; i < SETS; i++) {
      int j = i + random.nextInt(remaining.size() - i);
      LottoNumbers tmp = remaining.get(i);
      remaining.set(i, remaining.get(j));
      remaining.set(j, tmp);
    }
    return new GenerationOutcome.Generated(remaining.subList(0, SETS));
  }

  private LottoNumbers draw(int[] pool, int pick, Set<Integer> include) {
    int[] copy = pool.clone();
    List<Integer> values = new ArrayList<>(include);
    for (int i = 0; i < pick; i++) {
      int j = i + random.nextInt(copy.length - i);
      int tmp = copy[i];
      copy[i] = copy[j];
      copy[j] = tmp;
      values.add(copy[i]);
    }
    return LottoNumbers.of(values);
  }

  private static LottoNumbers combine(int[] pool, int[] index, Set<Integer> include) {
    List<Integer> values = new ArrayList<>(include);
    for (int i : index) {
      values.add(pool[i]);
    }
    return LottoNumbers.of(values);
  }

  private static boolean next(int[] index, int n) {
    int k = index.length;
    int i = k - 1;
    while (i >= 0 && index[i] == n - k + i) {
      i--;
    }
    if (i < 0) {
      return false;
    }
    index[i]++;
    for (int j = i + 1; j < k; j++) {
      index[j] = index[j - 1] + 1;
    }
    return true;
  }

  private static int[] pool(RecommendationCondition condition) {
    return IntStream.rangeClosed(LottoNumber.MIN, LottoNumber.MAX)
        .filter(n -> !condition.include().contains(n) && !condition.exclude().contains(n))
        .toArray();
  }

  private boolean expired(long start) {
    return nanoTime.getAsLong() - start > TIME_LIMIT.toNanos();
  }
}
