package com.lottery.lottery_service.lotto.validation.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.lottery.lottery_service.lotto.entity.LottoWinnerData;
import com.lottery.lottery_service.lotto.repository.LottoWinnerDataRepository;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * LottoWinnerDataCache의 단위 테스트.
 *
 * <p>이 테스트 클래스의 역할:
 *
 * <ul>
 *   <li>DB 전체 로드 결과를 캐시에 올바르게 반영하는지 검증한다.
 *   <li>증분 추가(add)가 기존 캐시를 유지한 채 새 값을 포함시키는지 검증한다.
 *   <li>contains(...)가 현재 캐시 상태를 정확히 조회하는지 검증한다.
 * </ul>
 *
 * <p>중요:
 *
 * <ul>
 *   <li>이 테스트는 실제 DB를 사용하지 않는다.
 *   <li>Repository는 mock으로 두고, cache의 메모리 상태 전이만 검증한다.
 *   <li>동시성 자체를 직접 검증하지는 않지만, 원자적 교체 설계가 의도대로 동작하는지 확인한다.
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class LottoWinnerDataCacheTest {

  @Mock private LottoWinnerDataRepository repo;

  @InjectMocks private LottoWinnerDataCache cache;

  /**
   * reloadAll()은 repository.findAll() 결과의 winnerData만 뽑아서 현재 캐시를 전량 교체해야 한다.
   *
   * <p>이 테스트가 보장하는 것:
   *
   * <ul>
   *   <li>repo.findAll()이 호출된다.
   *   <li>DB에 있는 canonical 문자열들이 캐시에 반영된다.
   *   <li>reloadAll() 호출 전 캐시에 있던 임시 값은 유지되지 않는다.
   * </ul>
   */
  @Test
  @DisplayName("전체 리로드는 DB winnerData 집합으로 캐시를 교체한다")
  void reloadAll_repositoryHasData_replacesCacheWithAllWinnerData() {
    // given
    LottoWinnerData round1111 =
        LottoWinnerData.builder()
            .round(1111)
            .winnerData("1 3 12 25 34 41")
            .bnusNo(7)
            .totSellamnt(1000L)
            .firstWinamnt(2000L)
            .firstPrzwnerCo(3)
            .firstAccumamnt(6000L)
            .build();

    LottoWinnerData round1112 =
        LottoWinnerData.builder()
            .round(1112)
            .winnerData("2 8 19 22 32 42")
            .bnusNo(11)
            .totSellamnt(1000L)
            .firstWinamnt(2000L)
            .firstPrzwnerCo(3)
            .firstAccumamnt(6000L)
            .build();

    // reloadAll이 "교체" 동작인지 보기 위해 사전 값 하나를 넣어둔다.
    cache.add("9 10 11 12 13 14");

    given(repo.findAll()).willReturn(List.of(round1111, round1112));

    // when
    cache.reloadAll();

    // then
    verify(repo).findAll();
    assertThat(cache.contains("1 3 12 25 34 41")).isTrue();
    assertThat(cache.contains("2 8 19 22 32 42")).isTrue();

    // 기존 임시 값은 유지되면 안 된다. reloadAll은 전량 교체이기 때문이다.
    assertThat(cache.contains("9 10 11 12 13 14")).isFalse();
  }

  /**
   * add(normalized)는 현재 캐시에 없는 새 canonical 문자열을 증분으로 추가해야 한다.
   *
   * <p>이 테스트가 보장하는 것:
   *
   * <ul>
   *   <li>기존 값은 유지된다.
   *   <li>새 값이 추가된다.
   *   <li>DB를 다시 조회하지 않고 메모리 캐시만 갱신한다.
   * </ul>
   */
  @Test
  @DisplayName("증분 추가는 기존 캐시를 유지하면서 새 조합을 포함시킨다")
  void add_newNormalizedWinner_keepsOldEntriesAndAddsNewEntry() {
    // given
    cache.add("1 3 12 25 34 41");

    // when
    cache.add("2 8 19 22 32 42");

    // then
    assertThat(cache.contains("1 3 12 25 34 41")).isTrue();
    assertThat(cache.contains("2 8 19 22 32 42")).isTrue();
  }

  /**
   * 같은 문자열을 여러 번 add(...) 해도 캐시는 Set 기반이므로 중복 저장 개념이 없어야 한다.
   *
   * <p>이 테스트가 보장하는 것:
   *
   * <ul>
   *   <li>중복 add가 발생해도 contains 결과는 안정적으로 true다.
   *   <li>Set 의미론을 깨뜨리지 않는다.
   * </ul>
   *
   * <p>참고: 내부 Set의 size를 외부에서 직접 볼 수 없으므로, 이 테스트는 "중복 추가가 이상 동작을 만들지 않는다"는 관점으로 검증한다.
   */
  @Test
  @DisplayName("같은 조합을 중복 추가해도 정상적으로 포함 상태를 유지한다")
  void add_duplicateNormalizedWinner_keepsStableContainsState() {
    // given
    String normalized = "1 3 12 25 34 41";

    // when
    cache.add(normalized);
    cache.add(normalized);

    // then
    assertThat(cache.contains(normalized)).isTrue();
  }

  /**
   * 아무 것도 로드되지 않은 초기 상태에서는 contains(...)가 false 여야 한다.
   *
   * <p>이 테스트가 보장하는 것:
   *
   * <ul>
   *   <li>초기 캐시는 빈 Set이다.
   *   <li>존재하지 않는 값 조회 시 false를 반환한다.
   * </ul>
   */
  @Test
  @DisplayName("초기 빈 캐시에서는 어떤 조합도 포함하지 않는다")
  void contains_cacheIsEmpty_returnsFalse() {
    assertThat(cache.contains("1 2 3 4 5 6")).isFalse();
  }

  /**
   * init()은 내부적으로 reloadAll()을 호출해야 한다.
   *
   * <p>이 테스트가 보장하는 것:
   *
   * <ul>
   *   <li>애플리케이션 시작 시 수행되는 초기화 동작이 실질적으로 전체 리로드와 동일하다.
   *   <li>init() 호출만으로 DB 데이터가 캐시에 올라온다.
   * </ul>
   */
  @Test
  @DisplayName("초기화 호출은 전체 리로드를 수행한다")
  void init_repositoryHasData_loadsAllWinnerDataIntoCache() {
    // given
    LottoWinnerData round1112 =
        LottoWinnerData.builder()
            .round(1112)
            .winnerData("2 8 19 22 32 42")
            .bnusNo(11)
            .totSellamnt(1000L)
            .firstWinamnt(2000L)
            .firstPrzwnerCo(3)
            .firstAccumamnt(6000L)
            .build();

    given(repo.findAll()).willReturn(List.of(round1112));

    // when
    cache.init();

    // then
    verify(repo).findAll();
    assertThat(cache.contains("2 8 19 22 32 42")).isTrue();
  }
}
