package com.lottery.lottery_service.lotto.external;

import com.lottery.lottery_service.lotto.entity.LottoWinnerData;
import com.lottery.lottery_service.lotto.external.dto.DhlotteryDrawResponse;
import com.lottery.lottery_service.lotto.repository.LottoWinnerDataRepository;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 동행복권 회차별 당첨 데이터를 DB와 동기화하는 서비스.
 *
 * <p>동작:
 *
 * <ul>
 *   <li>앱 시작 시: DB의 최신 round 확인 → 다음 round부터 API로 조회/저장(미발표 시 중단)
 *   <li>추후 스케줄링에서도 {@link #syncMissingRoundsOnce()} 재사용
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LottoWinnerSyncService {

  private final LottoWinnerDataRepository winnerRepo;
  private final DhlotteryClient client;

  /** 앱 기동 완료 후 한 번 실행. 추후에 이관 예정 DB 최신 회차 이후의 누락분을 API에서 조회해 저장한다. */
  @EventListener(ApplicationReadyEvent.class)
  @Transactional
  public void backfillOnStartup() {
    int saved = syncMissingRoundsOnce();
    log.info("Winner sync on startup finished. saved rounds: {}", saved);
  }

  /**
   * DB 최신 round의 다음 회차부터, API가 '미발표/없음'을 반환할 때까지 순차적으로 조회해 저장한다.
   *
   * @return 저장된 건수
   */
  @Transactional
  public int syncMissingRoundsOnce() {
    int latest = winnerRepo.findTopByOrderByRoundDesc().map(LottoWinnerData::getRound).orElse(0);
    int nextRound = latest + 1;

    int savedCount = 0;
    while (true) {
      Optional<DhlotteryDrawResponse> dtoOpt = client.fetchRound(nextRound);
      log.info("WinnerSync: fetching round {}", nextRound);
      if (dtoOpt.isEmpty()) {
        log.info("WinnerSync: stop at round {} (not available yet or fetch failed)", nextRound);
        // 더 이상 발표되지 않았거나 호출 실패 → 이번 라운드에서 중단
        break;
      }

      DhlotteryDrawResponse dto = dtoOpt.get();

      // 멱등성: 이미 존재하면 스킵 (여러 인스턴스/재시도 대비)
      if (winnerRepo.existsById(dto.getDrwNo())) {
        nextRound++;
        continue;
      }

      LottoWinnerData entity = client.toEntity(dto);
      winnerRepo.save(entity);
      savedCount++;
      nextRound++;
    }
    return savedCount;
  }
}
