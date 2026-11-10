package com.lottery.lottery_service.recommendation.generator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lottery.lottery_service.common.error.BusinessException;
import com.lottery.lottery_service.common.error.ErrorCode;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RecommendationConditionTest {

  @Test
  @DisplayName("후보 수는 C(45 − 넣을 수 − 뺄 수, 6 − 넣을 수)다")
  void candidateCount() {
    assertThat(RecommendationCondition.none().candidateCount()).isEqualTo(8_145_060L);
    assertThat(RecommendationCondition.of(List.of(1, 2, 3, 4, 5), range(6, 40)).candidateCount())
        .isEqualTo(5);
    assertThat(RecommendationCondition.of(List.of(1, 2, 3, 4, 5), range(6, 41)).candidateCount())
        .isEqualTo(4);
    assertThat(RecommendationCondition.of(List.of(), range(1, 39)).candidateCount()).isEqualTo(1);
  }

  @Test
  @DisplayName("넣을 번호 6개 이상, 뺄 번호 40개 이상, 양쪽에 같은 숫자, 범위 밖, 중복 입력은 CONDITION_INVALID")
  void invalidConditions() {
    assertInvalid(List.of(1, 2, 3, 4, 5, 6), List.of());
    assertInvalid(List.of(), range(1, 40));
    assertInvalid(List.of(7), List.of(7));
    assertInvalid(List.of(0), List.of());
    assertInvalid(List.of(), List.of(46));
    assertInvalid(List.of(3, 3), List.of());
    assertInvalid(Arrays.asList(1, null), List.of());
  }

  @Test
  @DisplayName("비어 있거나 null인 입력은 기본 추천이다")
  void defaultCondition() {
    assertThat(RecommendationCondition.of(null, null).isDefault()).isTrue();
    assertThat(RecommendationCondition.of(List.of(), List.of(1)).isDefault()).isFalse();
  }

  private static List<Integer> range(int from, int toInclusive) {
    return IntStream.rangeClosed(from, toInclusive).boxed().toList();
  }

  private static void assertInvalid(List<Integer> include, List<Integer> exclude) {
    assertThatThrownBy(() -> RecommendationCondition.of(include, exclude))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.getCode()).isEqualTo(ErrorCode.CONDITION_INVALID));
  }
}
