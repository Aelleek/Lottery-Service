package com.lottery.lottery_service.notification.budget;

import com.lottery.lottery_service.common.time.SeoulTime;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 메시지 비용 상태와 월 예산 카운터를 같은 트랜잭션에서 함께 바꾼다(05 8.4절). 호출한 쪽 트랜잭션 안에서 부른다. 메시지 상태 조건으로 갱신해 같은 전환이 두 번
 * 반영되지 않는다.
 *
 * <ul>
 *   <li>IN_FLIGHT가 될 때 예약(RESERVED). 상한을 넘으면 예약하지 않는다.
 *   <li>접수되면 확정 사용(SPENT). 확정 미접수·확실한 거절이면 반환(RELEASED). 접수 여부를 모르면 예약 그대로 둔다.
 * </ul>
 */
@Component
@EnableConfigurationProperties(BudgetProperties.class)
public class MessageBudgetStore {

  /** 기본 제공자. */
  public static final String PROVIDER = "SOLAPI";

  private final JdbcTemplate jdbc;
  private final BudgetProperties properties;

  /** 저장소를 만든다. */
  public MessageBudgetStore(JdbcTemplate jdbc, BudgetProperties properties) {
    this.jdbc = jdbc;
    this.properties = properties;
  }

  /**
   * 메시지 비용을 그 달 예산에서 예약한다. 그 달 행이 없으면 설정값으로 만든다.
   *
   * @param at 예약 시각. 서울 기준 달을 정한다
   * @return 예약했으면 {@code true}, 예산이 모자라거나 이미 예약된 메시지면 {@code false}
   */
  public boolean reserve(long messageId, MessagePurpose purpose, long units, Instant at) {
    LocalDate month = SeoulTime.monthOf(at).atDay(1);
    ensureMonth(month);
    int counter =
        jdbc.update(
            "UPDATE message_budget SET reserved_units = reserved_units + ?, version = version + 1"
                + " WHERE provider = ? AND business_month = ?"
                + " AND reserved_units + spent_units + ? <= LEAST(limit_units, ?)",
            units,
            PROVIDER,
            month,
            units,
            properties.capFor(purpose));
    if (counter == 0) {
      return false;
    }
    int message =
        jdbc.update(
            "UPDATE delivery_message SET cost_state = 'RESERVED', cost_units = ?, budget_month = ?,"
                + " version = version + 1 WHERE id = ? AND (cost_state IS NULL OR cost_state = 'RELEASED')",
            units,
            month,
            messageId);
    if (message == 0) {
      throw new IllegalStateException("message is already reserved: " + messageId);
    }
    return true;
  }

  /** 예약을 확정 사용으로 옮긴다. 예약 상태가 아니면 아무것도 바꾸지 않는다. */
  public boolean spend(long messageId) {
    return move(messageId, "SPENT", "spent_units = spent_units + ?,");
  }

  /** 예약을 돌려준다. 예약 상태가 아니면 아무것도 바꾸지 않는다. */
  public boolean release(long messageId) {
    return move(messageId, "RELEASED", "");
  }

  /**
   * 카운터와 메시지 비용 합계가 같은지 본다. 정리 worker가 지난달과 이번 달에 부른다.
   *
   * @return 같으면 {@code true}. 그 달 행이 없고 메시지도 없으면 {@code true}
   */
  public boolean matchesMessages(LocalDate month) {
    return compare(month).matches();
  }

  /** 그 달 예산 행이 없으면 설정값으로 만든다(07 1절 예산 월 전환). 이미 있으면 그대로 둔다. */
  public void ensureMonth(LocalDate month) {
    jdbc.update(
        "INSERT INTO message_budget (provider, business_month, limit_units) VALUES (?, ?, ?)"
            + " ON DUPLICATE KEY UPDATE provider = provider",
        PROVIDER,
        month.withDayOfMonth(1),
        properties.monthlyLimitUnits());
  }

  /** 그 달 카운터. 행이 없으면 빈 값. */
  public Optional<BudgetUsage> usage(LocalDate month) {
    return jdbc
        .query(
            "SELECT limit_units, reserved_units, spent_units FROM message_budget WHERE provider = ?"
                + " AND business_month = ?",
            (rs, i) ->
                new BudgetUsage(
                    rs.getLong("limit_units"),
                    rs.getLong("reserved_units"),
                    rs.getLong("spent_units")),
            PROVIDER,
            month)
        .stream()
        .findFirst();
  }

  /** 그 달 카운터와 메시지 비용 합계를 함께 읽는다. 그 달 행이 없으면 카운터는 0으로 본다. */
  public BudgetComparison compare(LocalDate month) {
    Map<String, Object> sums =
        jdbc.queryForMap(
            "SELECT COALESCE(SUM(CASE WHEN cost_state = 'RESERVED' THEN cost_units END), 0) AS r,"
                + " COALESCE(SUM(CASE WHEN cost_state = 'SPENT' THEN cost_units END), 0) AS s"
                + " FROM delivery_message WHERE budget_month = ?",
            month);
    List<Map<String, Object>> counter =
        jdbc.queryForList(
            "SELECT reserved_units, spent_units FROM message_budget WHERE provider = ?"
                + " AND business_month = ?",
            PROVIDER,
            month);
    long counterReserved =
        counter.isEmpty() ? 0 : ((Number) counter.get(0).get("reserved_units")).longValue();
    long counterSpent =
        counter.isEmpty() ? 0 : ((Number) counter.get(0).get("spent_units")).longValue();
    return new BudgetComparison(
        month,
        counterReserved,
        counterSpent,
        ((Number) sums.get("r")).longValue(),
        ((Number) sums.get("s")).longValue());
  }

  private boolean move(long messageId, String to, String spentClause) {
    List<Map<String, Object>> rows =
        jdbc.queryForList(
            "SELECT cost_units, budget_month FROM delivery_message WHERE id = ?"
                + " AND cost_state = 'RESERVED' FOR UPDATE",
            messageId);
    if (rows.isEmpty()) {
      return false;
    }
    long units = ((Number) rows.get(0).get("cost_units")).longValue();
    Object month = rows.get(0).get("budget_month");
    jdbc.update(
        "UPDATE delivery_message SET cost_state = ?, version = version + 1 WHERE id = ?",
        to,
        messageId);
    if (spentClause.isEmpty()) {
      jdbc.update(
          "UPDATE message_budget SET reserved_units = reserved_units - ?, version = version + 1"
              + " WHERE provider = ? AND business_month = ?",
          units,
          PROVIDER,
          month);
    } else {
      jdbc.update(
          "UPDATE message_budget SET "
              + spentClause
              + " reserved_units = reserved_units - ?, version = version + 1"
              + " WHERE provider = ? AND business_month = ?",
          units,
          units,
          PROVIDER,
          month);
    }
    return true;
  }
}
