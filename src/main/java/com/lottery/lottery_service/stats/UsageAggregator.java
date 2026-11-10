package com.lottery.lottery_service.stats;

import static com.lottery.lottery_service.common.db.DbValues.utc;

import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 원천 사건({@code usage_event})에서 기간 집계를 다시 계산해 {@code usage_aggregate}를 덮어쓴다(04 7절, C-28). 같은 계산을 두 번
 * 해도 값이 같다. VERIFIED가 된 집계는 덮어쓰지 않는다. 고유 이용자는 기간마다 그 기간 사건의 서로 다른 주체 수로 센다(일별을 더해 월별을 만들지 않는다).
 */
@Component
public class UsageAggregator {

  /** 고유 이용자 지표 이름. 회원과 비회원을 합친 주체 수다. */
  public static final String ACTIVE_SUBJECTS = "ACTIVE_SUBJECTS";

  /** 활동한 회원 수. */
  public static final String ACTIVE_MEMBERS = "ACTIVE_MEMBERS";

  /** 활동한 익명 브라우저(비회원 주체) 수. 실제 사람 수가 아니며 회원 수와 더하지 않는다. */
  public static final String ACTIVE_GUESTS = "ACTIVE_GUESTS";

  /** 지금 계산 버전. */
  public static final long CALCULATION_VERSION = 1;

  private static final String ALL = "ALL";

  private final JdbcTemplate jdbc;
  private final Clock clock;

  /** 집계기를 만든다. */
  public UsageAggregator(JdbcTemplate jdbc, Clock clock) {
    this.jdbc = jdbc;
    this.clock = clock;
  }

  /** 서울 기준 하루를 다시 계산한다. */
  public void recomputeDay(LocalDate day) {
    recompute("DAY", day.toString(), "business_date = ?", day);
  }

  /** 서울 기준 한 달을 다시 계산한다. */
  public void recomputeMonth(YearMonth month) {
    recompute(
        "MONTH",
        month.toString(),
        "business_date >= ? AND business_date <= ?",
        month.atDay(1),
        month.atEndOfMonth());
  }

  /** 한 회차를 다시 계산한다. */
  public void recomputeRound(int roundNo) {
    recompute("ROUND", Integer.toString(roundNo), "round_no = ?", roundNo);
  }

  /**
   * 원천과 대조해 같으면 그 기간 집계를 VERIFIED로 확정한다. 보정 기간이 지난 뒤에 부른다.
   *
   * @return 모든 지표가 원천과 같아 확정했으면 {@code true}
   */
  public boolean verify(String periodType, String periodKey) {
    List<Map<String, Object>> stored =
        jdbc.queryForList(
            "SELECT metric, value_count, state FROM usage_aggregate WHERE period_type = ?"
                + " AND period_key = ? AND dimension_key = ? AND calculation_version = ?",
            periodType,
            periodKey,
            ALL,
            CALCULATION_VERSION);
    Map<String, Long> source = sourceValues(periodType, periodKey);
    for (Map<String, Object> row : stored) {
      long expected = source.getOrDefault((String) row.get("metric"), 0L);
      if (((Number) row.get("value_count")).longValue() != expected) {
        return false;
      }
    }
    if (stored.size() < source.size()) {
      return false;
    }
    jdbc.update(
        "UPDATE usage_aggregate SET state = 'VERIFIED', sealed_at = ? WHERE period_type = ?"
            + " AND period_key = ? AND calculation_version = ? AND state = 'BUILDING'",
        utc(clock.instant()),
        periodType,
        periodKey,
        CALCULATION_VERSION);
    return true;
  }

  private void recompute(String periodType, String periodKey, String where, Object... args) {
    Map<String, Long> values = values(where, args);
    for (Map.Entry<String, Long> e : values.entrySet()) {
      jdbc.update(
          "INSERT INTO usage_aggregate (period_type, period_key, metric, dimension_key,"
              + " calculation_version, value_count, as_of) VALUES (?, ?, ?, ?, ?, ?, ?)"
              + " ON DUPLICATE KEY UPDATE"
              + " value_count = IF(state = 'VERIFIED', value_count, VALUES(value_count)),"
              + " as_of = IF(state = 'VERIFIED', as_of, VALUES(as_of))",
          periodType,
          periodKey,
          e.getKey(),
          ALL,
          CALCULATION_VERSION,
          e.getValue(),
          utc(clock.instant()));
    }
    List<Object> zeroArgs = new ArrayList<>(List.of(utc(clock.instant()), periodType, periodKey));
    String notIn = "";
    if (!values.isEmpty()) {
      notIn =
          " AND metric NOT IN ("
              + String.join(",", values.keySet().stream().map(k -> "?").toList())
              + ")";
      zeroArgs.addAll(values.keySet());
    }
    jdbc.update(
        "UPDATE usage_aggregate SET value_count = 0, as_of = ? WHERE period_type = ?"
            + " AND period_key = ? AND state = 'BUILDING'"
            + notIn,
        zeroArgs.toArray());
  }

  private Map<String, Long> sourceValues(String periodType, String periodKey) {
    return switch (periodType) {
      case "DAY" -> values("business_date = ?", LocalDate.parse(periodKey));
      case "MONTH" -> {
        YearMonth month = YearMonth.parse(periodKey);
        yield values(
            "business_date >= ? AND business_date <= ?", month.atDay(1), month.atEndOfMonth());
      }
      case "ROUND" -> values("round_no = ?", Integer.parseInt(periodKey));
      default -> throw new IllegalArgumentException("unknown period type: " + periodType);
    };
  }

  private Map<String, Long> values(String where, Object... args) {
    Map<String, Long> values = new TreeMap<>();
    jdbc.queryForList(
            "SELECT metric, SUM(delta) AS total FROM usage_event WHERE "
                + where
                + " GROUP BY metric",
            args)
        .forEach(r -> values.put((String) r.get("metric"), ((Number) r.get("total")).longValue()));
    List<Object> activityArgs = new ArrayList<>(List.of(args));
    activityArgs.addAll(UsageEventStore.ACTIVITY);
    String activity =
        " AND metric IN ("
            + String.join(",", UsageEventStore.ACTIVITY.stream().map(m -> "?").toList())
            + ")";
    Map<String, Object> active =
        jdbc.queryForMap(
            "SELECT COUNT(DISTINCT e.subject_id) AS total,"
                + " COUNT(DISTINCT CASE WHEN s.kind = 'MEMBER' THEN e.subject_id END) AS members,"
                + " COUNT(DISTINCT CASE WHEN s.kind = 'GUEST' THEN e.subject_id END) AS guests"
                + " FROM (SELECT subject_id FROM usage_event WHERE "
                + where
                + activity
                + ") e LEFT JOIN actor_subject s ON s.id = e.subject_id",
            activityArgs.toArray());
    putPositive(values, ACTIVE_SUBJECTS, active.get("total"));
    putPositive(values, ACTIVE_MEMBERS, active.get("members"));
    putPositive(values, ACTIVE_GUESTS, active.get("guests"));
    return values;
  }

  private static void putPositive(Map<String, Long> values, String metric, Object count) {
    long n = count == null ? 0 : ((Number) count).longValue();
    if (n > 0) {
      values.put(metric, n);
    }
  }
}
