package com.lottery.lottery_service.notification.budget;

import static org.assertj.core.api.Assertions.assertThat;

import com.lottery.lottery_service.common.job.JobProperties;
import com.lottery.lottery_service.common.job.JobStore;
import com.lottery.lottery_service.support.MySqlContainerSupport;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/** 매일 예산 카운터 대조와 불일치 알림을 MySQL에서 확인한다. */
class BudgetReconcilerMySqlTest extends MySqlContainerSupport {

  /** 서울 2026-12-01 04:00. */
  private static final Instant NOW = Instant.parse("2026-11-30T19:00:00Z");

  private static DataSource dataSource;

  private JdbcTemplate jdbc;
  private BudgetReconciler reconciler;

  @BeforeAll
  static void migrate() throws Exception {
    dataSource = migratedDatabase("budget_reconciler");
  }

  @BeforeEach
  void setUp() {
    jdbc = new JdbcTemplate(dataSource);
    jdbc.update("DELETE FROM job");
    jdbc.update("DELETE FROM message_budget");
    Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    DataSourceTransactionManager tm = new DataSourceTransactionManager(dataSource);
    reconciler =
        new BudgetReconciler(
            new MessageBudgetStore(jdbc, new BudgetProperties(300_000L, 90)),
            new JobStore(jdbc, tm, clock, new JobProperties(null, null)),
            clock);
  }

  @Test
  @DisplayName("행이 없고 메시지도 없으면 맞는 것으로 보고 알리지 않는다")
  void emptyMonthsMatch() {
    assertThat(reconciler.reconcile()).isEmpty();
    assertThat(alerts()).isZero();
  }

  @Test
  @DisplayName("서울 기준 지난달 카운터가 메시지 합계와 다르면 그 달 값으로 운영자 알림을 한 번만 등록한다")
  void alertsMismatchOncePerMonth() {
    jdbc.update(
        "INSERT INTO message_budget (provider, business_month, limit_units, reserved_units,"
            + " spent_units) VALUES ('SOLAPI', '2026-11-01', 300000, 0, 143)");

    assertThat(reconciler.reconcile())
        .containsExactly(new BudgetComparison(LocalDate.parse("2026-11-01"), 0, 143, 0, 0));
    reconciler.reconcile();

    assertThat(alerts()).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT JSON_UNQUOTE(JSON_EXTRACT(payload, '$.month')) FROM job", String.class))
        .isEqualTo("2026-11");
  }

  @Test
  @DisplayName("이번 달 사용(예약+확정)이 80%에 닿으면 달마다 한 번 알리고, 그 전에는 알리지 않는다")
  void alertsUsageThreshold() {
    jdbc.update(
        "INSERT INTO message_budget (provider, business_month, limit_units, reserved_units,"
            + " spent_units) VALUES ('SOLAPI', '2026-12-01', 1000, 100, 699)");
    assertThat(reconciler.alertUsage()).isFalse();

    jdbc.update("UPDATE message_budget SET spent_units = 700");
    assertThat(reconciler.alertUsage()).isTrue();
    assertThat(reconciler.alertUsage()).isFalse();
    assertThat(alerts()).isEqualTo(1);
  }

  private long alerts() {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM job WHERE job_type = 'OPERATOR_ALERT'", Long.class);
  }
}
