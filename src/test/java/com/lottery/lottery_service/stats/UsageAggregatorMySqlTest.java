package com.lottery.lottery_service.stats;

import static org.assertj.core.api.Assertions.assertThat;

import com.lottery.lottery_service.account.AccountStore;
import com.lottery.lottery_service.account.GuestCredentialStore;
import com.lottery.lottery_service.auth.OAuthProvider;
import com.lottery.lottery_service.support.MySqlContainerSupport;
import com.lottery.lottery_service.support.TestSecrets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/** 사용량 집계의 재계산과 확정을 MySQL에서 확인한다. */
class UsageAggregatorMySqlTest extends MySqlContainerSupport {

  private static final Instant DAY1 = Instant.parse("2026-11-08T01:00:00Z");
  private static final Instant DAY2 = Instant.parse("2026-11-09T01:00:00Z");

  private static DataSource dataSource;

  private JdbcTemplate jdbc;
  private UsageEventStore events;
  private UsageAggregator aggregator;
  private long a;
  private long b;

  @BeforeAll
  static void migrate() throws Exception {
    dataSource = migratedDatabase("usage_aggregate");
  }

  @BeforeEach
  void setUp() {
    jdbc = new JdbcTemplate(dataSource);
    for (String t :
        List.of(
            "usage_aggregate",
            "usage_event",
            "guest_credential",
            "oauth_account",
            "actor_subject",
            "member")) {
      jdbc.update("DELETE FROM " + t);
    }
    Clock clock = Clock.fixed(Instant.parse("2026-11-20T00:00:00Z"), ZoneOffset.UTC);
    events = new UsageEventStore(jdbc);
    aggregator = new UsageAggregator(jdbc, clock);
    GuestCredentialStore guests =
        new GuestCredentialStore(
            jdbc, new DataSourceTransactionManager(dataSource), clock, TestSecrets.keys());
    a = guests.issue().subjectId();
    b = guests.issue().subjectId();
    events.record(a, null, UsageEventStore.RECOMMENDATION_SETS, 5, DAY1);
    events.record(b, null, UsageEventStore.RECOMMENDATION_SETS, 5, DAY1);
    events.record(a, null, UsageEventStore.RECOMMENDATION_SETS, 5, DAY2);
    events.record(a, null, UsageEventStore.RECOMMENDATION_SUCCEEDED, 1, DAY1);
    events.record(b, null, UsageEventStore.RECOMMENDATION_SUCCEEDED, 1, DAY1);
    events.record(a, null, UsageEventStore.RECOMMENDATION_SUCCEEDED, 1, DAY2);
  }

  @Test
  @DisplayName("두 번 계산해도 값이 같고, 월 고유 이용자는 일별 고유 이용자의 합이 아니다(T21)")
  void recomputeIsIdempotent() {
    aggregator.recomputeDay(LocalDate.parse("2026-11-08"));
    aggregator.recomputeDay(LocalDate.parse("2026-11-08"));
    aggregator.recomputeDay(LocalDate.parse("2026-11-09"));
    aggregator.recomputeMonth(YearMonth.parse("2026-11"));

    assertThat(value("DAY", "2026-11-08", UsageEventStore.RECOMMENDATION_SETS)).isEqualTo(10);
    assertThat(value("DAY", "2026-11-08", UsageAggregator.ACTIVE_SUBJECTS)).isEqualTo(2);
    assertThat(value("DAY", "2026-11-09", UsageAggregator.ACTIVE_SUBJECTS)).isEqualTo(1);
    assertThat(value("MONTH", "2026-11", UsageAggregator.ACTIVE_SUBJECTS)).isEqualTo(2);
    assertThat(value("MONTH", "2026-11", UsageEventStore.RECOMMENDATION_SETS)).isEqualTo(15);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM usage_aggregate WHERE period_key = '2026-11-08'", Long.class))
        .isEqualTo(4);
  }

  @Test
  @DisplayName("기록 조회는 주체마다 하루 1건만 남고, 고유 이용자에 들어간다(02 9절)")
  void recordViewsCountAsActivityOncePerDay() {
    long c =
        new GuestCredentialStore(
                jdbc,
                new DataSourceTransactionManager(dataSource),
                Clock.fixed(DAY2, ZoneOffset.UTC),
                TestSecrets.keys())
            .issue()
            .subjectId();
    events.recordOncePerDay(c, UsageEventStore.RECORDS_VIEWED, DAY2);
    events.recordOncePerDay(c, UsageEventStore.RECORDS_VIEWED, DAY2.plusSeconds(60));

    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM usage_event WHERE metric = 'RECORDS_VIEWED'", Long.class))
        .isEqualTo(1);
    aggregator.recomputeDay(LocalDate.parse("2026-11-09"));
    assertThat(value("DAY", "2026-11-09", UsageAggregator.ACTIVE_SUBJECTS)).isEqualTo(2);
  }

  @Test
  @DisplayName("원천과 같으면 VERIFIED로 확정하고, 그 뒤 정기 계산은 확정 값을 덮어쓰지 않는다")
  void verifiedIsNotOverwritten() {
    aggregator.recomputeDay(LocalDate.parse("2026-11-08"));
    assertThat(aggregator.verify("DAY", "2026-11-08")).isTrue();

    events.record(a, null, UsageEventStore.RECOMMENDATION_SETS, 5, DAY1);
    aggregator.recomputeDay(LocalDate.parse("2026-11-08"));

    assertThat(value("DAY", "2026-11-08", UsageEventStore.RECOMMENDATION_SETS)).isEqualTo(10);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM usage_aggregate WHERE period_key = '2026-11-08' AND state = 'VERIFIED'",
                Long.class))
        .isEqualTo(4);
  }

  @Test
  @DisplayName("원천과 다르면 확정하지 않는다")
  void verifyRejectsMismatch() {
    aggregator.recomputeDay(LocalDate.parse("2026-11-08"));
    events.record(b, null, UsageEventStore.RECOMMENDATION_SETS, 5, DAY1);

    assertThat(aggregator.verify("DAY", "2026-11-08")).isFalse();
    aggregator.recomputeDay(LocalDate.parse("2026-11-08"));
    assertThat(aggregator.verify("DAY", "2026-11-08")).isTrue();
  }

  @Test
  @DisplayName("활동 사건만 고유 이용자로 세고 회원과 익명 브라우저를 나누며, 신규 회원과 추천 실패는 그대로 합한다(02 9)")
  void membersAndGuests() {
    long member =
        new AccountStore(
                jdbc,
                new DataSourceTransactionManager(dataSource),
                Clock.fixed(DAY1, ZoneOffset.UTC))
            .login(OAuthProvider.KAKAO, "m")
            .subjectId();
    events.record(member, null, UsageEventStore.PURCHASE_REGISTERED, 2, DAY1);
    events.record(b, null, UsageEventStore.RECOMMENDATION_FAILED, 1, DAY1);

    aggregator.recomputeDay(LocalDate.parse("2026-11-08"));

    assertThat(value("DAY", "2026-11-08", UsageAggregator.ACTIVE_MEMBERS)).isEqualTo(1);
    assertThat(value("DAY", "2026-11-08", UsageAggregator.ACTIVE_GUESTS)).isEqualTo(2);
    assertThat(value("DAY", "2026-11-08", UsageAggregator.ACTIVE_SUBJECTS)).isEqualTo(3);
    assertThat(value("DAY", "2026-11-08", UsageEventStore.MEMBER_REGISTERED)).isEqualTo(1);
    assertThat(value("DAY", "2026-11-08", UsageEventStore.RECOMMENDATION_FAILED)).isEqualTo(1);
  }

  private long value(String type, String key, String metric) {
    return jdbc.queryForObject(
        "SELECT value_count FROM usage_aggregate WHERE period_type = ? AND period_key = ? AND metric = ?",
        Long.class,
        type,
        key,
        metric);
  }
}
