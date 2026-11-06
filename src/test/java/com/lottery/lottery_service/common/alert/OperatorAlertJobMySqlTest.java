package com.lottery.lottery_service.common.alert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lottery.lottery_service.common.job.JobProperties;
import com.lottery.lottery_service.common.job.JobRunner;
import com.lottery.lottery_service.common.job.JobState;
import com.lottery.lottery_service.common.job.JobStore;
import com.lottery.lottery_service.support.MutableClock;
import com.lottery.lottery_service.support.MySqlContainerSupport;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/** 운영자 알림 job을 MySQL에서 확인한다. */
class OperatorAlertJobMySqlTest extends MySqlContainerSupport {

  private static DataSource dataSource;

  private final MutableClock clock = new MutableClock(Instant.parse("2026-11-14T12:00:00Z"));
  private final List<String> sent = new ArrayList<>();
  private JdbcTemplate jdbc;
  private DataSourceTransactionManager tm;
  private JobStore jobs;

  @BeforeAll
  static void migrate() throws Exception {
    dataSource = migratedDatabase("operator_alert");
  }

  @BeforeEach
  void setUp() {
    jdbc = new JdbcTemplate(dataSource);
    jdbc.update("DELETE FROM job");
    tm = new DataSourceTransactionManager(dataSource);
    jobs = new JobStore(jdbc, tm, clock, new JobProperties(1, Duration.ofMinutes(5)));
    jobs.enqueue("EVALUATE_RESULT", "result:1", "{}", clock.instant());
    jobs.fail(jobs.claim(List.of("EVALUATE_RESULT")).orElseThrow(), "SOURCE_DOWN", clock.instant());
  }

  @Test
  @DisplayName("DEAD가 된 job의 운영자 알림은 작업 종류와 마지막 오류만 담아 한 번 보낸다")
  void sendsDeadJobAlert() {
    JobRunner runner =
        new JobRunner(jobs, List.of(new OperatorAlertJobHandler(sent::add, jdbc)), tm, clock);

    assertThat(runner.runOnce()).contains(JobState.SUCCEEDED);
    assertThat(runner.runOnce()).isEmpty();
    assertThat(sent).hasSize(1);
    assertThat(sent.get(0)).contains("EVALUATE_RESULT").contains("SOURCE_DOWN");
  }

  @Test
  @DisplayName(
      "Discord 전송이 실패하면 그 알림 job만 실패로 남고(시도 상한 1이라 DEAD, 상한 안이면 다시 시도) 다른 job에는 영향이 없다(T42)")
  void webhookFailureRetries() {
    jobs.enqueue("OTHER", "x", "{}", clock.instant());
    OperatorAlertSender down =
        content -> {
          throw new IllegalStateException("discord down");
        };

    assertThat(
            new JobRunner(jobs, List.of(new OperatorAlertJobHandler(down, jdbc)), tm, clock)
                .runOnce())
        .contains(JobState.DEAD);
    assertThat(jdbc.queryForObject("SELECT state FROM job WHERE job_type = 'OTHER'", String.class))
        .isEqualTo("READY");
  }

  @Test
  @DisplayName("웹훅 주소가 없으면 보낼 때 실패한다")
  void unconfiguredWebhook() {
    assertThatThrownBy(() -> new DiscordWebhookSender("").send("x"))
        .isInstanceOf(IllegalStateException.class);
  }
}
