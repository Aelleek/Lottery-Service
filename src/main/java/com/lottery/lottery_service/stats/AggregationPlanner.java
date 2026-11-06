package com.lottery.lottery_service.stats;

import static com.lottery.lottery_service.common.db.DbValues.utc;

import com.lottery.lottery_service.common.time.SeoulTime;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 매시간 집계(07 1절). 최근 기간(오늘·어제, 이번 달, 보정 중인 지난달, 판매 중이거나 추첨 뒤 보정 중인 회차)을 원천에서 다시 계산하고, 보정 기간(02 9절)이
 * 끝난 기간은 한 번 더 계산한 뒤 원천과 대조해 VERIFIED로 확정한다. 보정 기간은 일·월은 기간이 끝난 뒤 7일, 회차는 추첨 뒤 7일이다.
 */
@Component
public class AggregationPlanner {

  /** 기간이 끝난 뒤 늦은 자료를 받는 기간. */
  public static final Duration CORRECTION = Duration.ofDays(7);

  private final JdbcTemplate jdbc;
  private final UsageAggregator aggregator;
  private final Clock clock;

  /** 계획기를 만든다. */
  public AggregationPlanner(JdbcTemplate jdbc, UsageAggregator aggregator, Clock clock) {
    this.jdbc = jdbc;
    this.aggregator = aggregator;
    this.clock = clock;
  }

  /**
   * 한 번 실행한다. 호출한 쪽 트랜잭션에서 돈다.
   *
   * @return 이번에 확정한 기간 수
   */
  public int run() {
    Instant now = clock.instant();
    LocalDate today = SeoulTime.atSeoul(now).toLocalDate();
    aggregator.recomputeDay(today);
    aggregator.recomputeDay(today.minusDays(1));
    YearMonth month = YearMonth.from(today);
    aggregator.recomputeMonth(month);
    if (correctionEnd(month.minusMonths(1)).isAfter(now)) {
      aggregator.recomputeMonth(month.minusMonths(1));
    }
    jdbc.queryForList(
            "SELECT round_no FROM lotto_round WHERE sales_open_at <= ? AND draw_at > ?",
            Integer.class,
            utc(now),
            utc(now.minus(CORRECTION)))
        .forEach(aggregator::recomputeRound);
    return verifyClosedPeriods(now);
  }

  private int verifyClosedPeriods(Instant now) {
    int verified = 0;
    List<Map<String, Object>> building =
        jdbc.queryForList(
            "SELECT DISTINCT period_type, period_key FROM usage_aggregate WHERE state = 'BUILDING'"
                + " AND calculation_version = ? AND period_type IN ('DAY', 'MONTH')",
            UsageAggregator.CALCULATION_VERSION);
    for (Map<String, Object> row : building) {
      String type = (String) row.get("period_type");
      String key = (String) row.get("period_key");
      Instant end =
          "DAY".equals(type)
              ? correctionEnd(LocalDate.parse(key))
              : correctionEnd(YearMonth.parse(key));
      if (end.isAfter(now)) {
        continue;
      }
      if ("DAY".equals(type)) {
        aggregator.recomputeDay(LocalDate.parse(key));
      } else {
        aggregator.recomputeMonth(YearMonth.parse(key));
      }
      verified += aggregator.verify(type, key) ? 1 : 0;
    }
    List<Integer> rounds =
        jdbc.queryForList(
            "SELECT r.round_no FROM lotto_round r WHERE r.draw_at <= ? AND NOT EXISTS (SELECT 1"
                + " FROM usage_aggregate a WHERE a.period_type = 'ROUND'"
                + " AND a.period_key = CAST(r.round_no AS CHAR) AND a.state = 'VERIFIED')"
                + " AND EXISTS (SELECT 1 FROM usage_event e WHERE e.round_no = r.round_no)"
                + " ORDER BY r.round_no",
            Integer.class,
            utc(now.minus(CORRECTION)));
    for (int round : rounds) {
      aggregator.recomputeRound(round);
      verified += aggregator.verify("ROUND", Integer.toString(round)) ? 1 : 0;
    }
    return verified;
  }

  private static Instant correctionEnd(LocalDate day) {
    return day.plusDays(1).atStartOfDay(SeoulTime.ZONE).toInstant().plus(CORRECTION);
  }

  private static Instant correctionEnd(YearMonth month) {
    return month.plusMonths(1).atDay(1).atStartOfDay(SeoulTime.ZONE).toInstant().plus(CORRECTION);
  }
}
