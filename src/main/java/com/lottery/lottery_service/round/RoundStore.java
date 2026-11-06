package com.lottery.lottery_service.round;

import static com.lottery.lottery_service.common.db.DbValues.instant;
import static com.lottery.lottery_service.common.db.DbValues.utc;

import com.lottery.lottery_service.common.error.BusinessException;
import com.lottery.lottery_service.common.error.ErrorCode;
import com.lottery.lottery_service.round.domain.RoundCalendar;
import com.lottery.lottery_service.round.domain.RoundSchedule;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@code lotto_round}의 일정과 마감 봉인을 다룬다. 기록 저장은 회차 행을 공유 잠금하고, 마감 봉인은 배타 잠금한다(04 4절).
 *
 * <p>저장 허용 여부는 잠금을 잡은 다음 읽은 DB 시각으로 판단한다(05 2절 7번). DB 세션 시간대는 UTC여야 한다(D-045).
 */
@Component
public class RoundStore {

  private static final String COLUMNS =
      "round_no, sales_open_at, sales_close_at, draw_at, schedule_version, cutoff_state,"
          + " cutoff_close_at";

  private final JdbcTemplate jdbc;
  private final TransactionTemplate tx;

  /** 저장소를 만든다. */
  public RoundStore(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
    this.jdbc = jdbc;
    this.tx = new TransactionTemplate(transactionManager);
  }

  /** 회차 일정을 등록한다. */
  public void register(RoundSchedule schedule) {
    jdbc.update(
        "INSERT INTO lotto_round (round_no, sales_open_at, sales_close_at, draw_at,"
            + " schedule_version) VALUES (?, ?, ?, ?, ?)",
        schedule.roundNo(),
        utc(schedule.salesOpenAt()),
        utc(schedule.salesCloseAt()),
        utc(schedule.drawAt()),
        schedule.scheduleVersion());
  }

  /** 등록된 일정 전체로 회차 달력을 만든다. */
  public RoundCalendar calendar() {
    return new RoundCalendar(
        jdbc.query("SELECT " + COLUMNS + " FROM lotto_round", (rs, n) -> schedule(rs)));
  }

  /**
   * 회차 행을 공유 잠금하고, 잠금을 잡은 다음 DB 시각을 읽는다. 호출한 쪽 트랜잭션 안에서 불러야 한다.
   *
   * @throws BusinessException 회차가 없으면 {@code ROUND_CHANGED}
   */
  public LockedRound lockShared(int roundNo) {
    List<LockedRound> rows =
        jdbc.query(
            "SELECT " + COLUMNS + " FROM lotto_round WHERE round_no = ? FOR SHARE",
            (rs, n) ->
                new LockedRound(
                    schedule(rs),
                    "SEALED".equals(rs.getString("cutoff_state")),
                    instant(rs.getObject("cutoff_close_at", LocalDateTime.class)),
                    null),
            roundNo);
    if (rows.isEmpty()) {
      throw new BusinessException(ErrorCode.ROUND_CHANGED, "회차 정보를 새로 확인해 주세요.");
    }
    LockedRound locked = rows.get(0);
    return new LockedRound(locked.schedule(), locked.sealed(), locked.cutoffCloseAt(), dbNow());
  }

  /**
   * 추천을 저장해도 되는지 확인한다. 회차를 공유 잠금한 다음 읽은 DB 시각이 판매 구간 안이고 일정 버전이 같아야 한다. 호출한 쪽 트랜잭션 안에서 불러야 한다.
   *
   * @return 저장 시각({@code accepted_at})
   * @throws BusinessException 마감이 지났거나 일정 버전이 바뀌었으면 {@code ROUND_CHANGED}
   */
  public Instant acceptRecommendation(int roundNo, long scheduleVersion) {
    LockedRound locked = lockShared(roundNo);
    new RoundCalendar(List.of(locked.schedule()))
        .requireRecommendationAccepted(roundNo, scheduleVersion, locked.dbNow());
    return locked.dbNow();
  }

  /**
   * 판매 마감 시각이 지난 OPEN 회차를 봉인한다. 회차 행을 배타 잠금하므로 진행 중인 기록 저장이 끝나기를 기다린다. 봉인할 때의 판매 마감 시각을 {@code
   * cutoff_close_at}에 고정한다.
   *
   * @return 이번에 봉인한 회차 번호
   */
  public List<Integer> sealDue() {
    return tx.execute(
        status -> {
          List<Integer> due =
              jdbc.queryForList(
                  "SELECT round_no FROM lotto_round WHERE cutoff_state = 'OPEN'"
                      + " AND sales_close_at <= NOW(6) ORDER BY round_no FOR UPDATE",
                  Integer.class);
          for (int roundNo : due) {
            jdbc.update(
                "UPDATE lotto_round SET cutoff_state = 'SEALED', cutoff_sealed_at = NOW(6),"
                    + " cutoff_close_at = sales_close_at WHERE round_no = ?",
                roundNo);
          }
          return due;
        });
  }

  /** DB 시각(UTC). */
  public Instant dbNow() {
    return instant(jdbc.queryForObject("SELECT NOW(6)", LocalDateTime.class));
  }

  private static RoundSchedule schedule(ResultSet rs) throws SQLException {
    return new RoundSchedule(
        rs.getInt("round_no"),
        instant(rs.getObject("sales_open_at", LocalDateTime.class)),
        instant(rs.getObject("sales_close_at", LocalDateTime.class)),
        instant(rs.getObject("draw_at", LocalDateTime.class)),
        rs.getLong("schedule_version"));
  }
}
