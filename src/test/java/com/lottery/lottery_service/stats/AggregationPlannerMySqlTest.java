package com.lottery.lottery_service.stats;

import static org.assertj.core.api.Assertions.assertThat;

import com.lottery.lottery_service.account.GuestCredentialStore;
import com.lottery.lottery_service.support.MutableClock;
import com.lottery.lottery_service.support.MySqlContainerSupport;
import com.lottery.lottery_service.support.TestSecrets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/** 매시간 집계의 재계산 범위와 보정 기간 뒤 확정을 MySQL에서 확인한다. */
class AggregationPlannerMySqlTest extends MySqlContainerSupport {

  private static final int ROUND = 1200;

  /** 서울 2026-11-10(화) 10:00. */
  private static final Instant TUESDAY = Instant.parse("2026-11-10T01:00:00Z");

  private static DataSource dataSource;

  private final MutableClock clock = new MutableClock(TUESDAY);
  private JdbcTemplate jdbc;
  private UsageEventStore events;
  private AggregationPlanner planner;
  private long subject;

  @BeforeAll
  static void migrate() throws Exception {
    dataSource = migratedDatabase("aggregation_planner");
  }

  @BeforeEach
  void setUp() {
    jdbc = new JdbcTemplate(dataSource);
    for (String t :
        List.of(
            "usage_aggregate", "usage_event", "guest_credential", "actor_subject", "lotto_round")) {
      jdbc.update("DELETE FROM " + t);
    }
    jdbc.update(
        "INSERT INTO lotto_round (round_no, sales_open_at, sales_close_at, draw_at, schedule_version)"
            + " VALUES (?, '2026-11-07 21:00:00', '2026-11-14 11:00:00', '2026-11-14 11:35:00', 1)",
        ROUND);
    events = new UsageEventStore(jdbc);
    planner = new AggregationPlanner(jdbc, new UsageAggregator(jdbc, clock), clock);
    subject =
        new GuestCredentialStore(
                jdbc, new DataSourceTransactionManager(dataSource), clock, TestSecrets.keys())
            .issue()
            .subjectId();
  }

  @Test
  @DisplayName("오늘·어제·이번 달·판매 중 회차를 다시 계산하고, 보정 기간 전에는 확정하지 않는다")
  void recomputesRecentPeriods() {
    events.record(subject, ROUND, UsageEventStore.RECOMMENDATION_SETS, 5, TUESDAY);
    events.record(
        subject, ROUND, UsageEventStore.RECOMMENDATION_SETS, 5, TUESDAY.minusSeconds(86_400));

    assertThat(planner.run()).isZero();

    assertThat(value("DAY", "2026-11-10")).isEqualTo(5);
    assertThat(value("DAY", "2026-11-09")).isEqualTo(5);
    assertThat(value("MONTH", "2026-11")).isEqualTo(10);
    assertThat(value("ROUND", "1200")).isEqualTo(10);
    assertThat(verified()).isZero();
  }

  @Test
  @DisplayName("보정 기간(일·월은 끝난 뒤 7일, 회차는 추첨 뒤 7일) 안의 늦은 자료는 반영하고, 지나면 다시 계산해 VERIFIED로 확정한다(T21)")
  void verifiesAfterCorrectionWindow() {
    events.record(subject, ROUND, UsageEventStore.RECOMMENDATION_SETS, 5, TUESDAY);
    planner.run();
    events.record(subject, ROUND, UsageEventStore.RECOMMENDATION_SETS, 5, TUESDAY);

    clock.set(Instant.parse("2026-11-17T14:59:00Z"));
    planner.run();
    assertThat(state("DAY", "2026-11-10")).isEqualTo("BUILDING");

    clock.set(Instant.parse("2026-11-17T15:00:00Z"));
    assertThat(planner.run()).isEqualTo(1);
    assertThat(state("DAY", "2026-11-10")).isEqualTo("VERIFIED");
    assertThat(value("DAY", "2026-11-10")).isEqualTo(10);
    assertThat(state("ROUND", "1200")).isEqualTo("BUILDING");

    events.record(
        subject,
        ROUND,
        UsageEventStore.RECOMMENDATION_SETS,
        5,
        Instant.parse("2026-11-14T01:00:00Z"));
    clock.set(Instant.parse("2026-11-21T11:35:00Z"));
    assertThat(planner.run()).isEqualTo(1);
    assertThat(state("ROUND", "1200")).isEqualTo("VERIFIED");
    assertThat(value("ROUND", "1200")).isEqualTo(15);
    assertThat(state("MONTH", "2026-11")).isEqualTo("BUILDING");

    events.record(subject, ROUND, UsageEventStore.RECOMMENDATION_SETS, 5, TUESDAY);
    clock.set(Instant.parse("2026-12-07T15:00:00Z"));
    planner.run();
    assertThat(state("MONTH", "2026-11")).isEqualTo("VERIFIED");
    assertThat(value("MONTH", "2026-11")).isEqualTo(20);
    assertThat(value("ROUND", "1200")).isEqualTo(15);
  }

  @Test
  @DisplayName("다음 달 첫 7일 동안은 지난달도 매번 다시 계산해 늦은 자료를 반영하고, 그동안은 확정하지 않는다")
  void recomputesPreviousMonthDuringCorrection() {
    Instant lastDayOfOctober = Instant.parse("2026-10-31T01:00:00Z");
    events.record(subject, ROUND, UsageEventStore.RECOMMENDATION_SETS, 5, lastDayOfOctober);
    clock.set(Instant.parse("2026-11-01T01:00:00Z"));
    planner.run();
    assertThat(value("MONTH", "2026-10")).isEqualTo(5);

    events.record(subject, ROUND, UsageEventStore.RECOMMENDATION_SETS, 5, lastDayOfOctober);
    clock.set(Instant.parse("2026-11-05T01:00:00Z"));
    planner.run();

    assertThat(value("MONTH", "2026-10")).isEqualTo(10);
    assertThat(state("MONTH", "2026-10")).isEqualTo("BUILDING");
  }

  @Test
  @DisplayName("매시간 실행이 하루 넘게 멈춰 집계가 없는 지난 날·달도 원천에서 계산하고, 보정 기간이 지났으면 확정한다")
  void backfillsMissedPeriods() {
    events.record(
        subject,
        ROUND,
        UsageEventStore.RECOMMENDATION_SETS,
        5,
        Instant.parse("2026-10-20T01:00:00Z"));
    events.record(
        subject, ROUND, UsageEventStore.RECOMMENDATION_SETS, 5, TUESDAY.minus(Duration.ofDays(3)));

    planner.run();

    assertThat(value("DAY", "2026-11-07")).isEqualTo(5);
    assertThat(state("DAY", "2026-11-07")).isEqualTo("BUILDING");
    assertThat(value("DAY", "2026-10-20")).isEqualTo(5);
    assertThat(state("DAY", "2026-10-20")).isEqualTo("VERIFIED");
    assertThat(value("MONTH", "2026-10")).isEqualTo(5);
    assertThat(state("MONTH", "2026-10")).isEqualTo("VERIFIED");
  }

  private long value(String type, String key) {
    return jdbc.queryForObject(
        "SELECT value_count FROM usage_aggregate WHERE period_type = ? AND period_key = ?"
            + " AND metric = ?",
        Long.class,
        type,
        key,
        UsageEventStore.RECOMMENDATION_SETS);
  }

  private String state(String type, String key) {
    return jdbc.queryForObject(
        "SELECT state FROM usage_aggregate WHERE period_type = ? AND period_key = ? AND metric = ?",
        String.class,
        type,
        key,
        UsageEventStore.RECOMMENDATION_SETS);
  }

  private long verified() {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM usage_aggregate WHERE state = 'VERIFIED'", Long.class);
  }
}
