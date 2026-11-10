package com.lottery.lottery_service.account.withdrawal;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 삭제 기록 읽기. 백업에서 DB를 되돌린 뒤 삭제를 다시 적용할 때 쓴다(04 9절, 07 4절 3번). */
public interface DeletionJournalReader {

  /**
   * 남아 있는 삭제 기록을 모두 읽는다.
   *
   * @throws RuntimeException 읽지 못했을 때. 그러면 복원을 멈추고 서비스를 열지 않는다
   */
  List<Entry> readAll();

  /**
   * 삭제 기록 한 건.
   *
   * @param scope 지우는 범위
   * @param memberPublicId 탈퇴한 회원 외부 ID
   * @param requestedAt 탈퇴 요청 시각
   */
  record Entry(DeletionScope scope, UUID memberPublicId, Instant requestedAt) {}
}
