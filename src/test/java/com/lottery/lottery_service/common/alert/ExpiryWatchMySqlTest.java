package com.lottery.lottery_service.common.alert;

import static org.assertj.core.api.Assertions.assertThat;

import com.lottery.lottery_service.common.job.JobProperties;
import com.lottery.lottery_service.common.job.JobStore;
import com.lottery.lottery_service.support.MutableClock;
import com.lottery.lottery_service.support.MySqlContainerSupport;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/** 인증서·연동 만료 알림을 MySQL에서 확인한다. */
class ExpiryWatchMySqlTest extends MySqlContainerSupport {

  private static DataSource dataSource;

  private final MutableClock clock = new MutableClock(Instant.parse("2026-11-30T15:00:00Z"));
  private JdbcTemplate jdbc;
  private ExpiryWatch watch;

  @BeforeAll
  static void migrate() throws Exception {
    dataSource = migratedDatabase("expiry_watch");
  }

  @BeforeEach
  void setUp() {
    jdbc = new JdbcTemplate(dataSource);
    jdbc.update("DELETE FROM job");
    watch =
        new ExpiryWatch(
            new ExpiryProperties(
                Map.of(
                    "solapi-api-key", LocalDate.parse("2026-12-31"),
                    "tls", LocalDate.parse("2027-06-01"))),
            new JobStore(
                jdbc,
                new DataSourceTransactionManager(dataSource),
                clock,
                new JobProperties(null, null)),
            clock);
  }

  @Test
  @DisplayName("서울 기준 남은 일수가 30·14·7·1일 단계에 들면 단계마다 한 번 알리고, 놓친 날은 다음 짧은 단계에서 알린다")
  void stages() {
    assertThat(watch.check()).isEqualTo(1);
    assertThat(watch.check()).isZero();
    clock.set(Instant.parse("2026-12-20T15:00:00Z"));
    assertThat(watch.check()).isEqualTo(1);
    clock.set(Instant.parse("2026-12-30T15:00:00Z"));
    assertThat(watch.check()).isEqualTo(1);

    assertThat(
            jdbc.queryForList(
                "SELECT JSON_EXTRACT(payload, '$.daysLeft') FROM job ORDER BY id", Integer.class))
        .containsExactly(30, 10, 0);
    assertThat(
            jdbc.queryForObject(
                "SELECT JSON_UNQUOTE(JSON_EXTRACT(payload, '$.name')) FROM job LIMIT 1",
                String.class))
        .isEqualTo("solapi-api-key");
  }

  @Test
  @DisplayName("설정이 없으면 아무것도 알리지 않는다")
  void empty() {
    assertThat(
            new ExpiryWatch(
                    new ExpiryProperties(null),
                    new JobStore(
                        jdbc,
                        new DataSourceTransactionManager(dataSource),
                        clock,
                        new JobProperties(null, null)),
                    clock)
                .check())
        .isZero();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM job", Long.class)).isZero();
    assertThat(List.of(30, 14, 7, 1)).isEqualTo(ExpiryWatch.THRESHOLDS);
  }
}
