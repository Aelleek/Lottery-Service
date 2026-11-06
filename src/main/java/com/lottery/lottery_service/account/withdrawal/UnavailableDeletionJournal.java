package com.lottery.lottery_service.account.withdrawal;

import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 삭제 기록 저장소가 정해지기 전의 기본 구현. 항상 실패해 탈퇴 차단은 유지하고 삭제 job은 다시 시도한 뒤 운영자 알림으로 이어진다(04 6.2절 2번). 저장소를 정하면
 * 이 구현을 바꾼다.
 */
@Component
public class UnavailableDeletionJournal implements DeletionJournal {

  @Override
  public void append(UUID memberPublicId, Instant requestedAt) {
    throw new IllegalStateException("deletion journal store is not configured");
  }
}
