package com.lottery.lottery_service.stats;

import static com.lottery.lottery_service.common.db.DbValues.instant;

import com.lottery.lottery_service.common.error.BusinessException;
import com.lottery.lottery_service.common.error.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 관리자 기본 지표(06 4절 {@code GET /admin/stats/daily}, {@code /admin/stats/rounds/{round}}). 일별 지표는
 * 집계({@code usage_aggregate})에서 읽고, 회차 판정·알림 건수는 지금 원천에서 센다. 권한 확인은 호출한 쪽에서 한다.
 */
@Component
public class AdminStatsQuery {

  /** 한 번에 조회할 수 있는 최대 일수. */
  public static final int MAX_DAYS = 92;

  private final JdbcTemplate jdbc;
  private final Clock clock;

  /** 조회기를 만든다. */
  public AdminStatsQuery(JdbcTemplate jdbc, Clock clock) {
    this.jdbc = jdbc;
    this.clock = clock;
  }

  /**
   * 기간의 일별 지표. 집계가 없는 날은 0이다.
   *
   * @throws BusinessException 시작이 끝보다 늦거나 92일을 넘으면 {@code VALIDATION_FAILED}
   */
  public DailyStats daily(LocalDate from, LocalDate to) {
    if (from == null
        || to == null
        || from.isAfter(to)
        || ChronoUnit.DAYS.between(from, to) >= MAX_DAYS) {
      throw new BusinessException(ErrorCode.VALIDATION_FAILED, "기간은 92일 안에서 골라 주세요.");
    }
    Map<String, Map<String, Long>> byDay = new HashMap<>();
    LocalDateTime[] asOf = new LocalDateTime[1];
    jdbc.query(
        "SELECT period_key, metric, value_count, as_of FROM usage_aggregate WHERE period_type = 'DAY'"
            + " AND dimension_key = 'ALL' AND calculation_version = ? AND period_key BETWEEN ? AND ?",
        rs -> {
          byDay
              .computeIfAbsent(rs.getString("period_key"), k -> new HashMap<>())
              .put(rs.getString("metric"), rs.getLong("value_count"));
          LocalDateTime at = rs.getObject("as_of", LocalDateTime.class);
          if (asOf[0] == null || at.isAfter(asOf[0])) {
            asOf[0] = at;
          }
        },
        UsageAggregator.CALCULATION_VERSION,
        from.toString(),
        to.toString());
    List<DailyRow> rows = new ArrayList<>();
    for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
      Map<String, Long> m = byDay.getOrDefault(day.toString(), Map.of());
      long successes = m.getOrDefault(UsageEventStore.RECOMMENDATION_SUCCEEDED, 0L);
      long failures = m.getOrDefault(UsageEventStore.RECOMMENDATION_FAILED, 0L);
      rows.add(
          new DailyRow(
              day,
              m.getOrDefault(UsageEventStore.MEMBER_REGISTERED, 0L),
              m.getOrDefault(UsageAggregator.ACTIVE_MEMBERS, 0L),
              m.getOrDefault(UsageAggregator.ACTIVE_GUESTS, 0L),
              successes + failures,
              successes,
              failures,
              m.getOrDefault(UsageEventStore.RECOMMENDATION_SETS, 0L),
              m.getOrDefault(UsageEventStore.PURCHASE_REGISTERED, 0L)));
    }
    return new DailyStats(
        from,
        to,
        rows,
        UsageAggregator.CALCULATION_VERSION,
        asOf[0] == null ? clock.instant() : instant(asOf[0]));
  }

  /**
   * 회차 지표. 생성 세트 수는 회차 집계에서, 판정과 알림 건수는 지금 원천에서 센다(정리된 낙첨 기록은 빠진다).
   *
   * @throws BusinessException 등록된 회차가 아니면 {@code NOT_FOUND}
   */
  public RoundStats round(int roundNo) {
    List<Integer> exists =
        jdbc.queryForList(
            "SELECT round_no FROM lotto_round WHERE round_no = ?", Integer.class, roundNo);
    if (exists.isEmpty()) {
      throw new BusinessException(ErrorCode.NOT_FOUND, "회차를 찾을 수 없습니다.");
    }
    List<Map<String, Object>> sets =
        jdbc.queryForList(
            "SELECT value_count, as_of FROM usage_aggregate WHERE period_type = 'ROUND'"
                + " AND period_key = ? AND metric = ? AND dimension_key = 'ALL'"
                + " AND calculation_version = ?",
            Integer.toString(roundNo),
            UsageEventStore.RECOMMENDATION_SETS,
            UsageAggregator.CALCULATION_VERSION);
    long[] ranks = new long[6];
    long[] counts = new long[2];
    jdbc.query(
        "SELECT e.record_id IS NOT NULL AS evaluated, COALESCE(e.rank_no, 0) AS rank_no,"
            + " COUNT(*) AS n FROM lotto_record l JOIN lotto_round r ON r.round_no = l.round_no"
            + " LEFT JOIN winning_evaluation e ON e.record_id = l.id"
            + " AND e.result_id = r.published_result_id AND e.record_version = l.version"
            + " WHERE l.round_no = ? AND l.deleted_at IS NULL GROUP BY evaluated, rank_no",
        rs -> {
          if (rs.getBoolean("evaluated")) {
            counts[0] += rs.getLong("n");
            ranks[rs.getInt("rank_no")] += rs.getLong("n");
          } else {
            counts[1] += rs.getLong("n");
          }
        },
        roundNo);
    Map<String, Object> messages =
        jdbc.queryForMap(
            "SELECT COALESCE(SUM(state IN ('ACCEPTED','DELIVERED')), 0) AS accepted,"
                + " COALESCE(SUM(state IN ('FAILED','HALTED')), 0) AS failed"
                + " FROM delivery_message WHERE round_no = ? AND purpose = 'RESULT'",
            roundNo);
    return new RoundStats(
        roundNo,
        sets.isEmpty() ? 0 : ((Number) sets.get(0).get("value_count")).longValue(),
        counts[0],
        counts[1],
        new RankCounts(ranks[1], ranks[2], ranks[3], ranks[4], ranks[5], ranks[0]),
        ((Number) messages.get("accepted")).longValue(),
        ((Number) messages.get("failed")).longValue(),
        UsageAggregator.CALCULATION_VERSION,
        sets.isEmpty() ? clock.instant() : instant((LocalDateTime) sets.get(0).get("as_of")));
  }

  /**
   * openapi {@code DailyStats}.
   *
   * @param from 시작일(서울)
   * @param to 끝일(서울, 포함)
   * @param days 날마다 한 줄
   * @param calculationVersion 계산 버전
   * @param asOf 집계 기준 시각
   */
  public record DailyStats(
      LocalDate from, LocalDate to, List<DailyRow> days, long calculationVersion, Instant asOf) {}

  /**
   * openapi {@code DailyStatRow}. 익명 브라우저 수는 사람 수가 아니며 회원 수와 더하지 않는다.
   *
   * @param date 날짜
   * @param newMembers 신규 회원
   * @param activeMembers 활동한 회원
   * @param guestBrowsers 활동한 익명 브라우저
   * @param recommendationAttempts 추천 시도(성공+실패)
   * @param recommendationSuccesses 추천 성공
   * @param recommendationFailures 추천 실패
   * @param generatedSets 생성 세트
   * @param purchaseCombinations 구매 등록 조합
   */
  public record DailyRow(
      LocalDate date,
      long newMembers,
      long activeMembers,
      long guestBrowsers,
      long recommendationAttempts,
      long recommendationSuccesses,
      long recommendationFailures,
      long generatedSets,
      long purchaseCombinations) {}

  /**
   * openapi {@code RoundStats}.
   *
   * @param round 회차
   * @param generatedSets 생성 세트(회차 집계)
   * @param evaluatedRecords 현재 판정이 있는 기록
   * @param pendingRecords 판정을 기다리는 기록
   * @param rankCounts 등수별 기록 수
   * @param notificationsAccepted 접수·전달된 결과 알림
   * @param notificationsFailed 실패·중단된 결과 알림
   * @param calculationVersion 계산 버전
   * @param asOf 집계 기준 시각
   */
  public record RoundStats(
      int round,
      long generatedSets,
      long evaluatedRecords,
      long pendingRecords,
      RankCounts rankCounts,
      long notificationsAccepted,
      long notificationsFailed,
      long calculationVersion,
      Instant asOf) {}

  /**
   * 등수별 기록 수.
   *
   * @param first 1등
   * @param second 2등
   * @param third 3등
   * @param fourth 4등
   * @param fifth 5등
   * @param losing 낙첨
   */
  public record RankCounts(
      long first, long second, long third, long fourth, long fifth, long losing) {}
}
