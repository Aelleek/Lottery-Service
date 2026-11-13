package com.lottery.lottery_service.account.withdrawal;

import static com.lottery.lottery_service.common.db.DbValues.bytes;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 백업에서 DB를 되돌린 뒤 하는 일(07 4절 3·4번). 삭제 기록을 다시 적용해 되살아난 탈퇴 회원을 다시 막고 삭제 job을 등록하며, 모든 회원 세션을 회수한다. 삭제
 * 기록을 읽지 못하면 예외로 멈춘다.
 */
@Component
public class RestoreReplay {

  private final JdbcTemplate jdbc;
  private final WithdrawalService withdrawals;

  /** 구성 요소로 만든다. */
  public RestoreReplay(JdbcTemplate jdbc, WithdrawalService withdrawals) {
    this.jdbc = jdbc;
    this.withdrawals = withdrawals;
  }

  /**
   * 삭제 기록을 다시 적용한다.
   *
   * @return 다시 막은 회원 수, 이미 탈퇴 처리 중이거나 끝난 회원 수, DB에 없는 회원 수
   */
  public Result replay(DeletionJournalReader journal) {
    int blocked = 0;
    int alreadyHandled = 0;
    int missing = 0;
    for (DeletionJournalReader.Entry entry : journal.readAll()) {
      List<Map<String, Object>> rows =
          jdbc.queryForList(
              "SELECT id, state FROM member WHERE public_id = ?",
              (Object) bytes(entry.memberPublicId()));
      if (rows.isEmpty()) {
        missing++;
      } else if ("ACTIVE".equals(rows.get(0).get("state"))) {
        withdrawals.block(((Number) rows.get(0).get("id")).longValue());
        blocked++;
      } else {
        alreadyHandled++;
      }
    }
    return new Result(blocked, alreadyHandled, missing);
  }

  /**
   * 모든 회원 세션을 회수한다. 서버 세션을 지우고 모든 회원의 {@code auth_version}을 올린다(C-32). 둘은 한 트랜잭션이다.
   *
   * @return 버전을 올린 회원 수
   */
  @Transactional
  public int revokeAllSessions() {
    jdbc.update("DELETE FROM SPRING_SESSION");
    return jdbc.update("UPDATE member SET auth_version = auth_version + 1");
  }

  /**
   * 다시 적용한 결과.
   *
   * @param blocked 다시 막고 삭제 job을 등록한 회원 수
   * @param alreadyHandled 이미 탈퇴 처리 중이거나 끝난 회원 수
   * @param missing DB에 없는 회원 수(삭제가 끝난 뒤 행까지 없어진 경우 등)
   */
  public record Result(int blocked, int alreadyHandled, int missing) {}
}
