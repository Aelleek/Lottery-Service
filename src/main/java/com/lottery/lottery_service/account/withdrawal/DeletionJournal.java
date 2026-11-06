package com.lottery.lottery_service.account.withdrawal;

import java.time.Instant;
import java.util.UUID;

/**
 * DB 밖의 삭제 기록(journal) 저장소(04 6.2절 2번, 9절). DB를 백업에서 되돌려도 함께 되돌아가지 않아야 한다. 저장소 종류는 출시 조건으로 따로
 * 정한다(07 5절).
 */
public interface DeletionJournal {

  /**
   * 회원 삭제 기록을 남기고 저장 확인을 받는다.
   *
   * @throws RuntimeException 저장을 확인하지 못했을 때. 탈퇴 차단은 유지되고 job이 다시 시도한다
   */
  void append(UUID memberPublicId, Instant requestedAt);
}
