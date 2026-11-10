package com.lottery.lottery_service.recommendation.filter;

import static org.assertj.core.api.Assertions.assertThat;

import com.lottery.lottery_service.common.number.LottoNumbers;
import com.lottery.lottery_service.recommendation.generator.GenerationOutcome;
import com.lottery.lottery_service.recommendation.generator.RecommendationCondition;
import com.lottery.lottery_service.recommendation.generator.RecommendationGenerator;
import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class GenerationFilterPipelineTest {

  private static final LottoNumbers PAST = LottoNumbers.of(3, 11, 19, 27, 36, 44);

  private final GenerationFilterPipeline pipeline =
      new GenerationFilterPipeline(
          List.of(
              new ThreeZoneFilter(),
              new DifferenceKindsFilter(),
              new OddEvenFilter(),
              new LowHighFilter(),
              new SameEndDigitFilter(),
              new EndDigitSumFilter(),
              new PastFirstPrizeFilter()));
  private final FilterContext context = new FilterContext(1200, Set.of(PAST));

  @Test
  @DisplayName("필터는 @Order 순서로 적용되고 버전은 FILTER_V1이다")
  void order() {
    assertThat(pipeline.ruleIds())
        .containsExactly(
            "PAST_FIRST_PRIZE",
            "END_DIGIT_SUM",
            "SAME_END_DIGIT",
            "LOW_HIGH",
            "ODD_EVEN",
            "DIFFERENCE_KINDS",
            "THREE_ZONE");
    assertThat(GenerationFilterPipeline.VERSION).isEqualTo("FILTER_V1");
  }

  @ParameterizedTest(name = "{0} -> {1}")
  @CsvSource({
    "'3 11 19 27 36 44', PAST_FIRST_PRIZE",
    "'10 20 30 40 41 42', END_DIGIT_SUM",
    "'1 11 21 31 5 9', SAME_END_DIGIT",
    "'1 3 5 8 13 20', LOW_HIGH",
    "'1 7 13 25 33 41', ODD_EVEN",
    "'2 9 16 23 30 37', DIFFERENCE_KINDS",
    "'16 18 20 22 25 29', THREE_ZONE"
  })
  @DisplayName("정책 문서의 예시는 앞 필터를 모두 통과하고 그 필터에서 처음 걸린다(02 2.2)")
  void policyExamples(String numbers, String expected) {
    assertThat(pipeline.firstFailure(parse(numbers), context)).contains(expected);
  }

  @Test
  @DisplayName("경계값: 끝수 합 14와 42, 같은 끝수 3개, 22와 23은 통과한다")
  void boundaries() {
    assertThat(pipeline.firstFailure(LottoNumbers.of(1, 2, 3, 4, 14, 29), context)).isEmpty();
    assertThat(pipeline.firstFailure(LottoNumbers.of(9, 8, 7, 6, 5, 27), context)).isEmpty();
    assertThat(pipeline.firstFailure(LottoNumbers.of(1, 11, 21, 4, 25, 38), context)).isEmpty();
    assertThat(pipeline.firstFailure(LottoNumbers.of(2, 7, 13, 22, 23, 38), context)).isEmpty();
  }

  @Test
  @DisplayName("과거 1등은 그 요청의 비교 자료에 있는 조합만 거른다")
  void pastFirstPrizeDependsOnContext() {
    FilterContext before = new FilterContext(1100, Set.of());

    assertThat(pipeline.firstFailure(PAST, before)).isEmpty();
    assertThat(pipeline.firstFailure(PAST, context)).contains("PAST_FIRST_PRIZE");
  }

  @Test
  @DisplayName("기본 추천 1,000회의 모든 세트가 필터 7개를 통과한다(T34의 생성 부분)")
  void generatedSetsPassAllFilters() {
    RecommendationGenerator generator =
        new RecommendationGenerator(new SplittableRandom(3), System::nanoTime);

    for (int i = 0; i < 1_000; i++) {
      GenerationOutcome outcome =
          generator.generate(
              RecommendationCondition.none(), pipeline.forContext(context), Set.of());
      assertThat(((GenerationOutcome.Generated) outcome).sets())
          .allSatisfy(s -> assertThat(pipeline.firstFailure(s, context)).isEmpty());
    }
  }

  private static LottoNumbers parse(String numbers) {
    return LottoNumbers.of(
        java.util.Arrays.stream(numbers.split(" ")).map(Integer::valueOf).toList());
  }
}
