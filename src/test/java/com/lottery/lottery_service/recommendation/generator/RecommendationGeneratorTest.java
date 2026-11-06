package com.lottery.lottery_service.recommendation.generator;

import static org.assertj.core.api.Assertions.assertThat;

import com.lottery.lottery_service.common.number.LottoNumbers;
import java.util.HashMap;
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
  @DisplayName("같은 seed면 같은 결과다(회귀 확인용)")
  void sameSeedSameResult() {
    RecommendationCondition condition = RecommendationCondition.of(List.of(7), List.of(8, 9));
    for (RecommendationCondition c : List.of(RecommendationCondition.none(), condition)) {
      assertThat(seeded(42).generate(c, CandidateFilter.NONE, Set.of()))
          .isEqualTo(seeded(42).generate(c, CandidateFilter.NONE, Set.of()));
    }
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

  private static List<Integer> range(int from, int toInclusive) {
    return IntStream.rangeClosed(from, toInclusive).boxed().toList();
  }
}
