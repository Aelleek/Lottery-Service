package com.lottery.lottery_service.recommendation.generator;

import static org.assertj.core.api.Assertions.assertThat;

import com.lottery.lottery_service.common.number.LottoNumbers;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RecommendationGeneratorTest {

  private static final List<Integer> FIVE = List.of(1, 2, 3, 4, 5);

  private final RecommendationGenerator generator = seeded(7);

  @Test
  @DisplayName("기본 추천은 서로 다르고 이력에 없으며 필터를 통과한 5세트다")
  void defaultRecommendation() {
    LottoNumbers seenSet = LottoNumbers.of(1, 2, 3, 4, 5, 6);
    CandidateFilter noOne = n -> !n.toList().contains(1);

    for (int i = 0; i < 200; i++) {
      GenerationOutcome outcome =
          generator.generate(RecommendationCondition.none(), noOne, Set.of(seenSet));
      List<LottoNumbers> sets = ((GenerationOutcome.Generated) outcome).sets();
      assertThat(sets).hasSize(5).doesNotHaveDuplicates().doesNotContain(seenSet);
      assertThat(sets).allSatisfy(s -> assertThat(s.toList()).doesNotContain(1));
    }
  }

  @Test
  @DisplayName("같은 seed라 같은 후보가 먼저 나와도 이미 받은 조합은 다시 주지 않는다(표본 추출 경로)")
  void samplingSkipsSeen() {
    List<LottoNumbers> first =
        ((GenerationOutcome.Generated)
                seeded(7).generate(RecommendationCondition.none(), CandidateFilter.NONE, Set.of()))
            .sets();

    List<LottoNumbers> again =
        ((GenerationOutcome.Generated)
                seeded(7)
                    .generate(
                        RecommendationCondition.none(),
                        CandidateFilter.NONE,
                        new java.util.HashSet<>(first)))
            .sets();

    assertThat(again).hasSize(5).doesNotContainAnyElementsOf(first);
  }

  @Test
  @DisplayName("넣을 번호 5개·뺄 번호 35개면 후보 5개로 성공, 뺄 번호 36개면 후보 4개로 부족(T02)")
  void t02() {
    RecommendationCondition five = RecommendationCondition.of(FIVE, range(6, 40));
    RecommendationCondition four = RecommendationCondition.of(FIVE, range(6, 41));

    GenerationOutcome ok = generator.generate(five, CandidateFilter.NONE, Set.of());
    assertThat(((GenerationOutcome.Generated) ok).sets())
        .extracting(LottoNumbers::key)
        .containsExactlyInAnyOrder(
            "01 02 03 04 05 41",
            "01 02 03 04 05 42",
            "01 02 03 04 05 43",
            "01 02 03 04 05 44",
            "01 02 03 04 05 45");
    assertThat(generator.generate(four, CandidateFilter.NONE, Set.of()))
        .isEqualTo(new GenerationOutcome.Exhausted(4));
  }

  @Test
  @DisplayName("후보 5개 중 1개를 이미 받았으면 새 5세트를 만들 수 없다(T02)")
  void t02WithHistory() {
    RecommendationCondition five = RecommendationCondition.of(FIVE, range(6, 40));
    Set<LottoNumbers> seen = Set.of(LottoNumbers.of(1, 2, 3, 4, 5, 43));

    assertThat(generator.generate(five, CandidateFilter.NONE, seen))
        .isEqualTo(new GenerationOutcome.Exhausted(4));
  }

  @Test
  @DisplayName("조건 추천에는 생성 필터를 적용하지 않는다(D-028)")
  void conditionIgnoresFilter() {
    RecommendationCondition five = RecommendationCondition.of(FIVE, range(6, 40));
    CandidateFilter rejectAll = n -> false;

    assertThat(generator.generate(five, rejectAll, Set.of()))
        .isInstanceOf(GenerationOutcome.Generated.class);
    assertThat(
            generator.generate(
                RecommendationCondition.of(List.of(7), List.of()), rejectAll, Set.of()))
        .isInstanceOf(GenerationOutcome.Generated.class);
  }

  @Test
  @DisplayName("필터가 모두 거르면 1,000번 뒤 계산 지연이고 후보 부족과 구분된다")
  void attemptsExhaustedIsTimeout() {
    assertThat(generator.generate(RecommendationCondition.none(), n -> false, Set.of()))
        .isInstanceOf(GenerationOutcome.TimedOut.class);
  }

  @Test
  @DisplayName("250ms가 지나면 계산 지연으로 멈춘다")
  void timeLimit() {
    AtomicLong now = new AtomicLong();
    RecommendationGenerator slow =
        new RecommendationGenerator(new SplittableRandom(1), () -> now.getAndAdd(60_000_000L));

    assertThat(slow.generate(RecommendationCondition.none(), n -> false, Set.of()))
        .isInstanceOf(GenerationOutcome.TimedOut.class);
    assertThat(now.get()).isLessThanOrEqualTo(6 * 60_000_000L);
  }

  @Test
  @DisplayName("전체 나열 경로에서도 250ms를 넘기면 계산 지연이고, 정확히 250ms는 아직 아니다")
  void enumerationTimeLimit() {
    RecommendationCondition twenty = RecommendationCondition.of(List.of(), range(21, 45));
    long limit = RecommendationGenerator.TIME_LIMIT.toNanos();

    assertThat(twenty.candidateCount())
        .isBetween(1_025L, RecommendationGenerator.ENUMERATION_LIMIT);
    assertThat(
            new RecommendationGenerator(new SplittableRandom(1), sequence(0, limit))
                .generate(twenty, CandidateFilter.NONE, Set.of()))
        .isInstanceOf(GenerationOutcome.Generated.class);
    assertThat(
            new RecommendationGenerator(new SplittableRandom(1), sequence(0, limit + 1))
                .generate(twenty, CandidateFilter.NONE, Set.of()))
        .isInstanceOf(GenerationOutcome.TimedOut.class);
  }

  @Test
  @DisplayName("전체 나열은 3개를 고르는 경우에도 이력에 없는 조합을 빠짐없이 찾는다")
  void enumerationVisitsEveryCombination() {
    RecommendationCondition twenty = RecommendationCondition.of(List.of(1, 2, 3), range(10, 45));
    Set<LottoNumbers> remaining =
        Set.of(
            LottoNumbers.of(1, 2, 3, 4, 5, 6),
            LottoNumbers.of(1, 2, 3, 4, 8, 9),
            LottoNumbers.of(1, 2, 3, 5, 6, 7),
            LottoNumbers.of(1, 2, 3, 6, 8, 9),
            LottoNumbers.of(1, 2, 3, 7, 8, 9));
    Set<LottoNumbers> seen = new HashSet<>();
    for (int a = 4; a <= 9; a++) {
      for (int b = a + 1; b <= 9; b++) {
        for (int c = b + 1; c <= 9; c++) {
          seen.add(LottoNumbers.of(1, 2, 3, a, b, c));
        }
      }
    }
    assertThat(seen).hasSize(20);
    seen.removeAll(remaining);

    GenerationOutcome outcome = generator.generate(twenty, CandidateFilter.NONE, seen);

    assertThat(((GenerationOutcome.Generated) outcome).sets())
        .containsExactlyInAnyOrderElementsOf(remaining);
  }

  @Test
  @DisplayName("같은 seed면 같은 결과다(회귀 확인용)")
  void sameSeedSameResult() {
    RecommendationCondition condition = RecommendationCondition.of(List.of(7), List.of(8, 9));
    for (RecommendationCondition c : List.of(RecommendationCondition.none(), condition)) {
      assertThat(seeded(42).generate(c, CandidateFilter.NONE, Set.of()))
          .isEqualTo(seeded(42).generate(c, CandidateFilter.NONE, Set.of()));
    }
  }

  @Test
  @DisplayName("seed 42의 결과를 고정해, 뽑는 방식이 바뀌면 회귀 테스트가 알린다(08 3절)")
  void seed42Golden() {
    RecommendationCondition condition = RecommendationCondition.of(List.of(7), List.of(8, 9));
    assertThat(
            keys(
                seeded(42)
                    .generate(RecommendationCondition.none(), CandidateFilter.NONE, Set.of())))
        .containsExactly(
            "18 23 34 37 40 42",
            "10 17 30 33 37 42",
            "03 12 15 27 33 44",
            "08 25 26 27 37 44",
            "21 32 33 36 38 44");
    assertThat(keys(seeded(42).generate(condition, CandidateFilter.NONE, Set.of())))
        .containsExactly(
            "02 04 07 17 25 27",
            "04 07 10 33 37 45",
            "04 07 12 17 20 36",
            "07 12 15 22 32 33",
            "07 12 19 20 24 26");
  }

  private static List<String> keys(GenerationOutcome outcome) {
    return ((GenerationOutcome.Generated) outcome).sets().stream().map(LottoNumbers::key).toList();
  }

  @Test
  @DisplayName("작은 후보 공간에서 금지 조합은 나오지 않고 남은 조합은 고르게 나온다")
  void uniformOverSmallSpace() {
    RecommendationCondition ten = RecommendationCondition.of(FIVE, range(16, 45));
    Set<LottoNumbers> seen =
        Set.of(
            LottoNumbers.of(1, 2, 3, 4, 5, 6),
            LottoNumbers.of(1, 2, 3, 4, 5, 10),
            LottoNumbers.of(1, 2, 3, 4, 5, 15));
    Map<String, Integer> counts = new HashMap<>();
    int runs = 7_000;

    for (int i = 0; i < runs; i++) {
      GenerationOutcome outcome = generator.generate(ten, CandidateFilter.NONE, seen);
      for (LottoNumbers s : ((GenerationOutcome.Generated) outcome).sets()) {
        counts.merge(s.key(), 1, Integer::sum);
      }
    }

    assertThat(counts).hasSize(7).doesNotContainKeys("01 02 03 04 05 06", "01 02 03 04 05 10");
    double expected = runs * 5.0 / 7.0;
    assertThat(counts.values())
        .allSatisfy(c -> assertThat(c).isBetween((int) (expected * 0.95), (int) (expected * 1.05)));
  }

  @Test
  @DisplayName("기본 추천에서 각 숫자가 나오는 비율은 6/45에 가깝다")
  void numberFrequencyNearUniform() {
    int[] counts = new int[46];
    int runs = 4_000;
    for (int i = 0; i < runs; i++) {
      GenerationOutcome outcome =
          generator.generate(RecommendationCondition.none(), CandidateFilter.NONE, Set.of());
      for (LottoNumbers s : ((GenerationOutcome.Generated) outcome).sets()) {
        s.toList().forEach(n -> counts[n]++);
      }
    }
    double expected = runs * 5 * 6 / 45.0;
    IntStream.rangeClosed(1, 45)
        .forEach(
            n -> assertThat(counts[n]).isBetween((int) (expected * 0.9), (int) (expected * 1.1)));
  }

  private static RecommendationGenerator seeded(long seed) {
    return new RecommendationGenerator(new SplittableRandom(seed), System::nanoTime);
  }

  private static java.util.function.LongSupplier sequence(long first, long rest) {
    AtomicLong calls = new AtomicLong();
    return () -> calls.getAndIncrement() == 0 ? first : rest;
  }

  private static List<Integer> range(int from, int toInclusive) {
    return IntStream.rangeClosed(from, toInclusive).boxed().toList();
  }
}
