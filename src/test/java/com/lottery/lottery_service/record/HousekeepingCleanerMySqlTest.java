package com.lottery.lottery_service.record;

import static org.assertj.core.api.Assertions.assertThat;

import com.lottery.lottery_service.account.GuestCredential;
import com.lottery.lottery_service.account.GuestCredentialStore;
import com.lottery.lottery_service.common.job.JobProperties;
import com.lottery.lottery_service.common.job.JobStore;
import com.lottery.lottery_service.stats.UsageEventStore;
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

/** 매일 보관 정리의 나머지 대상을 MySQL에서 확인한다. */
class HousekeepingCleanerMySqlTest extends MySqlContainerSupport {

  private static final Instant T0 = Instant.parse("2026-11-01T00:00:00Z");

  private static DataSource dataSource;

  private final MutableClock clock = new MutableClock(T0);
  private JdbcTemplate jdbc;
  private DataSourceTransactionManager tm;

  @BeforeAll
  static void migrate() throws Exception {
    dataSource = migratedDatabase("housekeeping");
  }

  @BeforeEach
  void setUp() {
    jdbc = new JdbcTemplate(dataSource);
    for (String t :
        List.of(
            "job",
            "usage_event",
            "recommendation_seen",
            "recommendation_item",
            "recommendation_request",
            "lotto_record",
            "guest_credential",
            "actor_subject",
            "lotto_round")) {
      jdbc.update("DELETE FROM " + t);
    }
    tm = new DataSourceTransactionManager(dataSource);
  }

  @Test
  @DisplayName("만료 후 7일이 지난 자격과, 자료가 없는 비회원 주체를 지우고 사용량 사건은 주체 없이 남긴다")
  void guests() {
    GuestCredentialStore guests = new GuestCredentialStore(jdbc, tm, clock, TestSecrets.keys());
    GuestCredential expired = guests.issue();
    clock.set(T0.plus(Duration.ofDays(10)));
    GuestCredential active = guests.issue();
    new UsageEventStore(jdbc).record(expired.subjectId(), null, "RECOMMENDATION_SETS", 5, T0);
    HousekeepingCleaner cleaner = new HousekeepingCleaner(jdbc, clock);

    clock.set(expired.expiresAt().plus(Duration.ofDays(7)).minusSeconds(1));
    assertThat(cleaner.deleteExpiredGuestCredentials()).isZero();
    clock.set(expired.expiresAt().plus(Duration.ofDays(7)));
    assertThat(cleaner.deleteExpiredGuestCredentials()).isEqualTo(1);
    assertThat(cleaner.deleteEmptyGuestSubjects(100)).isEqualTo(1);

    assertThat(jdbc.queryForList("SELECT id FROM actor_subject", Long.class))
        .containsExactly(active.subjectId());
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM usage_event WHERE subject_id IS NULL", Long.class))
        .isEqualTo(1);
  }

  @Test
  @DisplayName("성공 후 30일이 지난 job만 지우고 DEAD와 대기 중인 job은 남긴다")
  void jobs() {
    JobStore jobs = new JobStore(jdbc, tm, clock, new JobProperties(1, Duration.ofMinutes(5)));
    jobs.enqueue("A", "done", "{}", T0);
    jobs.enqueue("A", "dead", "{}", T0);
    jobs.enqueue("A", "ready", "{}", T0.plus(Duration.ofDays(100)));
    jobs.complete(jobs.claim(List.of("A")).orElseThrow());
    jobs.fail(jobs.claim(List.of("A")).orElseThrow(), "X", T0);
    jdbc.update("DELETE FROM job WHERE job_type = 'OPERATOR_ALERT'");

    clock.set(T0.plus(Duration.ofDays(30)));
    assertThat(new HousekeepingCleaner(jdbc, clock).deleteFinishedJobs()).isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM job", Long.class)).isEqualTo(2);
  }

  @Test
  @DisplayName("기록이 남지 않은 주체의 마감 지난 회차 추천 이력·요청만 지우고, 마감 전 회차는 건드리지 않는다(D-025)")
  void roundHistory() {
    long subject =
        new GuestCredentialStore(jdbc, tm, clock, TestSecrets.keys()).issue().subjectId();
    jdbc.update(
        "INSERT INTO lotto_round (round_no, sales_open_at, sales_close_at, draw_at, schedule_version)"
            + " VALUES (1199, '2026-10-01', '2026-10-05', '2026-10-05', 1), (1200, '2026-10-05', '2026-12-31', '2026-12-31', 1)");
    for (int round : List.of(1199, 1200)) {
      jdbc.update(
          "INSERT INTO recommendation_request (public_id, subject_id, round_no, key_digest, request_hash,"
              + " accepted_at, policy_version, generation_mode, filter_version, response_expires_at)"
              + " VALUES (UUID_TO_BIN(UUID()), ?, ?, ?, ?, NOW(6), 1, 'BASIC', 'FILTER_V1', NOW(6))",
          subject,
          round,
          new byte[32],
          new byte[32]);
      long request = jdbc.queryForObject("SELECT MAX(id) FROM recommendation_request", Long.class);
      jdbc.update(
          "INSERT INTO recommendation_seen (subject_id, round_no, numbers_key, first_request_id)"
              + " VALUES (?, ?, '01 02 03 04 05 06', ?)",
          subject,
          round,
          request);
    }
    HousekeepingCleaner cleaner = new HousekeepingCleaner(jdbc, clock);

    assertThat(cleaner.deleteOrphanRoundHistory(1199)).isEqualTo(1);
    assertThat(cleaner.deleteOrphanRoundHistory(1200)).isZero();
    assertThat(jdbc.queryForList("SELECT round_no FROM recommendation_seen", Integer.class))
        .containsExactly(1200);
  }
}
