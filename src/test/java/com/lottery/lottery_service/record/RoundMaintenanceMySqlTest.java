package com.lottery.lottery_service.record;

import static org.assertj.core.api.Assertions.assertThat;

import com.lottery.lottery_service.account.AccountStore;
import com.lottery.lottery_service.account.GuestCredentialStore;
import com.lottery.lottery_service.auth.OAuthProvider;
import com.lottery.lottery_service.common.job.JobProperties;
import com.lottery.lottery_service.common.job.JobStore;
import com.lottery.lottery_service.common.number.LottoNumber;
import com.lottery.lottery_service.common.number.LottoNumbers;
import com.lottery.lottery_service.notification.NotificationPlanCloser;
import com.lottery.lottery_service.result.domain.WinningNumbers;
import com.lottery.lottery_service.result.evaluate.EvaluationStore;
import com.lottery.lottery_service.result.publish.PrizeProperties;
import com.lottery.lottery_service.result.publish.ResultPublisher;
import com.lottery.lottery_service.result.source.OfficialDraw;
import com.lottery.lottery_service.support.MutableClock;
import com.lottery.lottery_service.support.MySqlContainerSupport;
import com.lottery.lottery_service.support.TestSecrets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 매일 정리의 회차 작업(알림 계획 완료, 기록 정리, 정리 지연 알림)을 MySQL에서 확인한다. */
class RoundMaintenanceMySqlTest extends MySqlContainerSupport {

  private static final int ROUND = 1200;

  /** 추첨(2026-11-14) 뒤 30일이 지난 시각. */
  private static final Instant AFTER_RETENTION = Instant.parse("2026-12-15T00:00:00Z");

  /** 추첨(2026-11-14 11:35Z) 뒤 정확히 37일. */
  private static final Instant AFTER_DELAY = Instant.parse("2026-12-21T11:35:00Z");

  private static DataSource dataSource;

  private final MutableClock clock = new MutableClock(AFTER_RETENTION);
  private JdbcTemplate jdbc;
  private DataSourceTransactionManager tm;
  private TransactionTemplate tx;
  private RoundMaintenance maintenance;
  private long member;
  private long guest;

  @BeforeAll
  static void migrate() throws Exception {
    dataSource = migratedDatabase("round_maintenance");
  }

  @BeforeEach
  void setUp() {
    jdbc = new JdbcTemplate(dataSource);
    for (String t :
        List.of(
            "usage_aggregate",
            "winning_evaluation",
            "job",
            "lotto_record",
            "oauth_account",
            "guest_credential",
            "actor_subject",
            "member")) {
      jdbc.update("DELETE FROM " + t);
    }
    jdbc.update("UPDATE lotto_round SET published_result_id = NULL");
    jdbc.update("DELETE FROM draw_prize");
    jdbc.update("DELETE FROM draw_result");
    jdbc.update("DELETE FROM lotto_round");
    jdbc.update(
        "INSERT INTO lotto_round (round_no, sales_open_at, sales_close_at, draw_at, schedule_version,"
            + " cutoff_state, cutoff_sealed_at, cutoff_close_at) VALUES (?, '2026-11-07 21:00:00',"
            + " '2026-11-14 11:00:00', '2026-11-14 11:35:00', 1, 'SEALED', '2026-11-14 11:00:00',"
            + " '2026-11-14 11:00:00')",
        ROUND);
    jdbc.update(
        "INSERT INTO usage_aggregate (period_type, period_key, metric, dimension_key,"
            + " calculation_version, value_count, as_of, state) VALUES ('ROUND', ?,"
            + " 'RECOMMENDATION_SETS', 'ALL', 1, 5, NOW(6), 'VERIFIED')",
        Integer.toString(ROUND));
    tm = new DataSourceTransactionManager(dataSource);
    tx = new TransactionTemplate(tm);
    Clock setupClock = Clock.fixed(Instant.parse("2026-11-10T00:00:00Z"), ZoneOffset.UTC);
    member = new AccountStore(jdbc, tm, setupClock).login(OAuthProvider.KAKAO, "m").subjectId();
    guest = new GuestCredentialStore(jdbc, tm, setupClock, TestSecrets.keys()).issue().subjectId();
    maintenance =
        new RoundMaintenance(
            jdbc,
            new NotificationPlanCloser(jdbc, clock),
            new RecordRetentionCleaner(jdbc, clock),
            new JobStore(jdbc, tm, clock, new JobProperties(null, null)),
            tm,
            clock);
  }

  @Test
  @DisplayName("알림 계획을 완료한 뒤 보관 기간이 지난 낙첨·비회원 기록을 묶음마다 지우고, 당첨·구매 기록은 남긴다")
  void closesPlanAndCleansInBatches() {
    record(member, LottoNumbers.of(1, 2, 10, 20, 30, 40), false);
    record(member, LottoNumbers.of(1, 2, 11, 21, 31, 41), false);
    record(guest, LottoNumbers.of(1, 2, 12, 22, 32, 42), false);
    long fourth = record(member, LottoNumbers.of(1, 2, 3, 4, 20, 30), false);
    long bought = record(member, LottoNumbers.of(1, 2, 13, 23, 33, 43), true);
    publishAndEvaluate();

    assertThat(maintenance.cleanupRecords(1)).isZero();
    assertThat(maintenance.closeNotificationPlans()).isEqualTo(1);
    assertThat(maintenance.closeNotificationPlans()).isZero();
    assertThat(maintenance.cleanupRecords(1)).isEqualTo(3);

    assertThat(jdbc.queryForList("SELECT id FROM lotto_record ORDER BY id", Long.class))
        .containsExactly(fourth, bought);
    clock.set(AFTER_DELAY);
    assertThat(maintenance.alertDelayedCleanup()).isZero();
  }

  @Test
  @DisplayName("보관 기간 뒤 7일이 지나도 못 지운 기록은 회차·이유마다 운영자 알림 한 번이고, 그 전에는 알리지 않는다")
  void alertsDelayedCleanupOnce() {
    record(member, LottoNumbers.of(1, 2, 10, 20, 30, 40), false);
    record(guest, LottoNumbers.of(1, 2, 12, 22, 32, 42), false);
    record(member, LottoNumbers.of(1, 2, 3, 4, 20, 30), false);
    publishAndEvaluate();

    clock.set(AFTER_DELAY.minusSeconds(1));
    assertThat(maintenance.alertDelayedCleanup()).isZero();
    clock.set(AFTER_DELAY);
    assertThat(maintenance.alertDelayedCleanup()).isEqualTo(1);
    assertThat(maintenance.alertDelayedCleanup()).isZero();
    assertThat(alertPayloads())
        .containsExactly(
            "{\"kind\": \"CLEANUP_DELAYED\", \"count\": 2, \"reason\": \"NOTIFICATION_PLAN_OPEN\","
                + " \"roundNo\": 1200}");
  }

  @Test
  @DisplayName("회차 조건을 채웠는데 남은 기록은 미판정과 정정 보호로 나눠 알린다")
  void alertsRecordLevelReasons() {
    long protectedRecord = record(member, LottoNumbers.of(1, 2, 10, 20, 30, 40), false);
    publishAndEvaluate();
    record(member, LottoNumbers.of(1, 2, 11, 21, 31, 41), false);
    jdbc.update(
        "UPDATE lotto_record SET correction_protected_until = '2027-01-30 00:00:00' WHERE id = ?",
        protectedRecord);
    jdbc.update("UPDATE lotto_round SET notification_plan_closed_at = '2026-11-22 00:00:00'");
    clock.set(AFTER_DELAY);

    assertThat(maintenance.cleanupRecords(100)).isZero();
    assertThat(maintenance.alertDelayedCleanup()).isEqualTo(2);
    assertThat(alertPayloads())
        .containsExactlyInAnyOrder(
            "{\"kind\": \"CLEANUP_DELAYED\", \"count\": 1, \"reason\": \"UNEVALUATED\","
                + " \"roundNo\": 1200}",
            "{\"kind\": \"CLEANUP_DELAYED\", \"count\": 1, \"reason\": \"CORRECTION_PROTECTED\","
                + " \"roundNo\": 1200}");
  }

  private List<String> alertPayloads() {
    return jdbc.queryForList(
        "SELECT CAST(payload AS CHAR) FROM job WHERE job_type = 'OPERATOR_ALERT' ORDER BY id",
        String.class);
  }

  private void publishAndEvaluate() {
    Clock publishClock = Clock.fixed(Instant.parse("2026-11-14T12:00:00Z"), ZoneOffset.UTC);
    new ResultPublisher(
            jdbc,
            tm,
            new JobStore(jdbc, tm, publishClock, new JobProperties(null, null)),
            new PrizeProperties(null, null),
            publishClock)
        .receive(
            new OfficialDraw(
                ROUND,
                new WinningNumbers(LottoNumbers.of(1, 2, 3, 4, 5, 6), LottoNumber.of(7)),
                null,
                null,
                new byte[32]));
    long resultId = jdbc.queryForObject("SELECT published_result_id FROM lotto_round", Long.class);
    tx.execute(s -> new EvaluationStore(jdbc).evaluateBatch(resultId, 0, 100));
    jdbc.update("DELETE FROM job");
  }

  private long record(long subject, LottoNumbers numbers, boolean purchased) {
    jdbc.update(
        "INSERT INTO lotto_record (public_id, owner_subject_id, round_no, numbers_key, recommended_at,"
            + " purchased, purchased_at, created_at) VALUES (UUID_TO_BIN(UUID()), ?, ?, ?,"
            + " '2026-11-10 00:00:00', ?, "
            + (purchased ? "'2026-11-10 00:00:00'" : "NULL")
            + ", '2026-11-10 00:00:00')",
        subject,
        ROUND,
        numbers.key(),
        purchased);
    return jdbc.queryForObject("SELECT MAX(id) FROM lotto_record", Long.class);
  }
}
