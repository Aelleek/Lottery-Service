package com.lottery.lottery_service.stats;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lottery.lottery_service.account.AccountStore;
import com.lottery.lottery_service.account.GuestCredentialStore;
import com.lottery.lottery_service.auth.OAuthProvider;
import com.lottery.lottery_service.common.error.BusinessException;
import com.lottery.lottery_service.common.error.ErrorCode;
import com.lottery.lottery_service.common.job.JobProperties;
import com.lottery.lottery_service.common.job.JobStore;
import com.lottery.lottery_service.common.number.LottoNumber;
import com.lottery.lottery_service.common.number.LottoNumbers;
import com.lottery.lottery_service.result.domain.WinningNumbers;
import com.lottery.lottery_service.result.evaluate.EvaluationStore;
import com.lottery.lottery_service.result.publish.PrizeProperties;
import com.lottery.lottery_service.result.publish.ResultPublisher;
import com.lottery.lottery_service.result.source.OfficialDraw;
import com.lottery.lottery_service.support.MySqlContainerSupport;
import com.lottery.lottery_service.support.TestSecrets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import javax.sql.DataSource;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 관리자 기본 지표 조회를 MySQL에서 확인한다. */
class AdminStatsQueryMySqlTest extends MySqlContainerSupport {

  private static final int ROUND = 1200;
  private static final Instant DAY1 = Instant.parse("2026-11-08T01:00:00Z");

  private static DataSource dataSource;

  private final Clock clock = Clock.fixed(Instant.parse("2026-11-20T00:00:00Z"), ZoneOffset.UTC);
  private JdbcTemplate jdbc;
  private DataSourceTransactionManager tm;
  private AdminStatsQuery query;

  @BeforeAll
  static void migrate() throws Exception {
    dataSource = migratedDatabase("admin_stats");
  }

  @BeforeEach
  void setUp() {
    jdbc = new JdbcTemplate(dataSource);
    for (String t :
        List.of(
            "usage_aggregate",
            "usage_event",
            "delivery_message",
            "winning_evaluation",
            "job",
            "lotto_record",
            "guest_credential",
            "oauth_account",
            "actor_subject",
            "member")) {
      jdbc.update("DELETE FROM " + t);
    }
    jdbc.update("UPDATE lotto_round SET published_result_id = NULL");
    jdbc.update("DELETE FROM draw_prize");
    jdbc.update("DELETE FROM draw_result");
    jdbc.update("DELETE FROM lotto_round");
    jdbc.update(
        "INSERT INTO lotto_round (round_no, sales_open_at, sales_close_at, draw_at, schedule_version)"
            + " VALUES (?, '2026-11-07 21:00:00', '2026-11-14 11:00:00', '2026-11-14 11:35:00', 1)",
        ROUND);
    tm = new DataSourceTransactionManager(dataSource);
    query = new AdminStatsQuery(jdbc, clock);
  }

  @Test
  @DisplayName("일별 지표는 집계에서 읽고 집계 없는 날은 0, 추천 시도는 성공+실패, 기간이 거꾸로거나 92일을 넘으면 VALIDATION_FAILED")
  void daily() {
    Clock dayClock = Clock.fixed(DAY1, ZoneOffset.UTC);
    long member = new AccountStore(jdbc, tm, dayClock).login(OAuthProvider.KAKAO, "m").subjectId();
    long guest =
        new GuestCredentialStore(jdbc, tm, dayClock, TestSecrets.keys()).issue().subjectId();
    UsageEventStore events = new UsageEventStore(jdbc);
    events.record(guest, ROUND, UsageEventStore.RECOMMENDATION_SUCCEEDED, 1, DAY1);
    events.record(guest, ROUND, UsageEventStore.RECOMMENDATION_SETS, 5, DAY1);
    events.record(guest, ROUND, UsageEventStore.RECOMMENDATION_FAILED, 1, DAY1);
    events.record(member, ROUND, UsageEventStore.PURCHASE_REGISTERED, 3, DAY1);
    new UsageAggregator(jdbc, clock).recomputeDay(LocalDate.parse("2026-11-08"));

    AdminStatsQuery.DailyStats stats =
        query.daily(LocalDate.parse("2026-11-07"), LocalDate.parse("2026-11-08"));

    assertThat(stats.days())
        .containsExactly(
            new AdminStatsQuery.DailyRow(LocalDate.parse("2026-11-07"), 0, 0, 0, 0, 0, 0, 0, 0),
            new AdminStatsQuery.DailyRow(LocalDate.parse("2026-11-08"), 1, 1, 1, 2, 1, 1, 5, 3));
    assertThat(stats.calculationVersion()).isEqualTo(1);
    assertCode(
        () -> query.daily(LocalDate.parse("2026-11-09"), LocalDate.parse("2026-11-08")),
        ErrorCode.VALIDATION_FAILED);
    assertThat(query.daily(LocalDate.parse("2026-01-01"), LocalDate.parse("2026-04-02")).days())
        .hasSize(AdminStatsQuery.MAX_DAYS);
    assertCode(
        () -> query.daily(LocalDate.parse("2026-01-01"), LocalDate.parse("2026-04-03")),
        ErrorCode.VALIDATION_FAILED);
  }

  @Test
  @DisplayName("회차 지표는 현재 판정으로 판정 완료·대기와 등수별 건수, 결과 알림 접수·실패 건수를 세고, 없는 회차는 NOT_FOUND")
  void round() {
    long guest = new GuestCredentialStore(jdbc, tm, clock, TestSecrets.keys()).issue().subjectId();
    for (LottoNumbers n :
        List.of(LottoNumbers.of(1, 2, 3, 4, 20, 30), LottoNumbers.of(10, 20, 30, 40, 41, 42))) {
      record(guest, n);
    }
    new ResultPublisher(
            jdbc,
            tm,
            new JobStore(jdbc, tm, clock, new JobProperties(null, null)),
            new PrizeProperties(null, null),
            clock)
        .receive(
            new OfficialDraw(
                ROUND,
                new WinningNumbers(LottoNumbers.of(1, 2, 3, 4, 5, 6), LottoNumber.of(7)),
                null,
                null,
                new byte[32]));
    long result = jdbc.queryForObject("SELECT published_result_id FROM lotto_round", Long.class);
    new TransactionTemplate(tm)
        .execute(s -> new EvaluationStore(jdbc).evaluateBatch(result, 0, 100));
    record(guest, LottoNumbers.of(1, 2, 3, 10, 20, 30));
    AccountStore accounts = new AccountStore(jdbc, tm, clock);
    message(accounts.login(OAuthProvider.KAKAO, "a").memberId(), "ACCEPTED");
    message(accounts.login(OAuthProvider.KAKAO, "b").memberId(), "FAILED");

    AdminStatsQuery.RoundStats stats = query.round(ROUND);

    assertThat(stats.evaluatedRecords()).isEqualTo(2);
    assertThat(stats.pendingRecords()).isEqualTo(1);
    assertThat(stats.rankCounts()).isEqualTo(new AdminStatsQuery.RankCounts(0, 0, 0, 1, 0, 1));
    assertThat(stats.notificationsAccepted()).isEqualTo(1);
    assertThat(stats.notificationsFailed()).isEqualTo(1);
    assertCode(() -> query.round(ROUND + 1), ErrorCode.NOT_FOUND);
  }

  private void record(long subject, LottoNumbers numbers) {
    jdbc.update(
        "INSERT INTO lotto_record (public_id, owner_subject_id, round_no, numbers_key,"
            + " recommended_at) VALUES (UUID_TO_BIN(UUID()), ?, ?, ?, NOW(6))",
        subject,
        ROUND,
        numbers.key());
  }

  private void message(long member, String state) {
    jdbc.update(
        "INSERT INTO delivery_message (public_id, purpose, member_id, round_no, state,"
            + " provider_request_key, expires_at) VALUES (UUID_TO_BIN(UUID()), 'RESULT', ?, ?, ?,"
            + " UUID_TO_BIN(UUID()), NOW(6))",
        member,
        ROUND,
        state);
  }

  private static void assertCode(ThrowingCallable call, ErrorCode code) {
    assertThatThrownBy(call)
        .isInstanceOfSatisfying(
            BusinessException.class, e -> assertThat(e.getCode()).isEqualTo(code));
  }
}
