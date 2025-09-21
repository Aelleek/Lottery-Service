package com.lottery.lottery_service.lotto.validation.cache;

import com.lottery.lottery_service.lotto.entity.LottoWinnerData;
import com.lottery.lottery_service.lotto.repository.LottoWinnerDataRepository;
import jakarta.annotation.PostConstruct;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * [컴포넌트] 과거 "1등 당첨 조합"의 정규화 문자열 Set 캐시.
 *
 * <p>포맷(아주 중요) - DB의 LottoWinnerData.winnerData 포맷과 "정확히 동일": "오름차순 정렬 + 공백(' ') 조인" → 예: "1 3 12
 * 25 34 41"
 *
 * <p>목적 - HashSet 기반으로 포함 여부를 O(1)로 빠르게 확인하기 위함. - 룰에서 DB를 직접 두드리지 않고, 이 캐시만 읽도록 하여 성능/안정성 확보.
 *
 * <p>동시성/일관성 - AtomicReference<Set<String>>로 불변 Set을 원자적으로 교체한다(읽기에는 락이 필요 없음). - 초기 로드 실패는 애플리케이션
 * 비정상 상태이므로 조기에 감지되어야 한다(예외 처리 권장).
 */
@Component
public class LottoWinnerDataCache {

  private final LottoWinnerDataRepository repo;

  /**
   * 현재 캐시된 "정규화 문자열" 집합. - 초기값은 빈 Set. - init() → reloadAll()에서 전량 로드하여 교체한다. - 불변 Set을 저장해 외부 변경을
   * 원천 차단한다.
   */
  private final AtomicReference<Set<String>> normalizedWinnersRef = new AtomicReference<>(Set.of());

  public LottoWinnerDataCache(LottoWinnerDataRepository repo) {
    this.repo = repo;
  }

  /** 앱 기동 시 1회 호출되어 전체 회차를 로드한다. - 운영환경에서 실패 시 문제를 조기에 드러내는 편이 안전하다. */
  @PostConstruct
  public void init() {
    reloadAll();
  }

  /** 전체 리로드: DB에서 모든 회차의 winnerData를 읽어, 불변 Set으로 교체한다. - 관리자/스케줄러 훅으로도 호출할 수 있다. */
  public void reloadAll() {
    Set<String> all =
        repo.findAll().stream()
            // LottoWinnerData.getWinnerData()는 이미 "정규화 포맷" 문자열을 돌려줘야 한다.
            .map(LottoWinnerData::getWinnerData)
            .collect(Collectors.toUnmodifiableSet());
    normalizedWinnersRef.set(all); // 원자적 교체
  }

  /** 증분 추가: 새 회차 동기화 직후, 해당 회차의 정규화 문자열을 1건 추가한다. - copy-on-write 전략(작은 비용, 읽기 가벼움 유지) */
  public void add(String normalized) {
    Set<String> oldSet = normalizedWinnersRef.get();
    Set<String> newSet = oldSet.stream().collect(Collectors.toSet()); // 가변 복사
    newSet.add(normalized);
    normalizedWinnersRef.set(Set.copyOf(newSet)); // 불변으로 교체
  }

  /**
   * 포함 여부 조회(O(1)).
   *
   * @param normalized "오름차순 + 공백 조인" 정규화 문자열
   * @return 캐시에 존재하면 true (즉, 과거 1등과 완전 동일)
   */
  public boolean contains(String normalized) {
    return normalizedWinnersRef.get().contains(normalized);
  }
}
