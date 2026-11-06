package com.lottery.lottery_service.record;

import static org.assertj.core.api.Assertions.assertThat;

import com.lottery.lottery_service.account.AccountStore;
import com.lottery.lottery_service.account.GuestCredentialStore;
import com.lottery.lottery_service.auth.OAuthProvider;
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

/** 보관 기간 정리와 삭제 순서를 MySQL에서 확인한다. */
class RecordRetentionCleanerMySqlTest extends MySqlContainerSupport {

  private static final int ROUND = 1200;
  private static final Instant AFTER_RETENTION = Instant.parse("2026-12-15T00:00:00Z");
  private static final String LOSING = LottoNumbers.of(1, 2, 10, 20, 30, 40).key();
  private static final String FOURTH = LottoNumbers.of(1, 2, 3, 4, 20, 30).key();

  private static DataSource dataSource;

  private JdbcTemplate jdbc;
  private DataSourceTransactionManager tm;
  private TransactionTemplate tx;
  private long member;
  private long guest;

  @BeforeAll
  static void migrate() throws Exception {
    dataSource = migratedDatabase("record_retention");
  }

  @BeforeEach
  void setUp() {
    jdbc = new JdbcTemplate(dataSource);
    for (String t :
        List.of(
            "usage_aggregate",
            "winning_evaluation",
            "recommendation_seen",
            "recommendation_item",
            "recommendation_request",
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
            + " notification_plan_closed_at) VALUES (?, '2026-11-07 21:00:00', '2026-11-14 11:00:00',"
            + " '2026-11-14 11:35:00', 1, '2026-11-16 00:00:00')",
        ROUND);
    jdbc.update(
        "INSERT INTO usage_aggregate (period_type, period_key, metric, dimension_key,"
            + " calculation_version, value_count, as_of, state) VALUES ('ROUND', ?, 'RECOMMENDATION_SETS',"
            + " 'ALL', 1, 5, NOW(6), 'VERIFIED')",
        Integer.toString(ROUND));
    tm = new DataSourceTransactionManager(dataSource);
    tx = new TransactionTemplate(tm);
    Clock clock = Clock.fixed(Instant.parse("2026-11-14T12:00:00Z"), ZoneOffset.UTC);
    member = new AccountStore(jdbc, tm, clock).login(OAuthProvider.KAKAO, "m").subjectId();
    guest = new GuestCredentialStore(jdbc, tm, clock, TestSecrets.keys()).issue().subjectId();
  }

  @Test
  @DisplayName("낙첨 추천 기록은 추천 항목과 판정을 먼저 지운 뒤 삭제되고, 4등 기록과 구매 기록은 남는다(T41)")
  void deletesLosingRecommendations() {
    long losing = record(member, LOSING, false);
    long fourth = record(member, FOURTH, false);
    long bought = record(member, LottoNumbers.of(1, 2, 11, 21, 31, 41).key(), true);
    long guestRecord = record(guest, LottoNumbers.of(1, 2, 3, 4, 20, 30).key(), false);
    recommendationItem(losing);
    publishAndEvaluate();
    record(member, LottoNumbers.of(1, 2, 12, 22, 32, 42).key(), false);

    int deleted = tx.execute(s -> cleaner(AFTER_RETENTION).cleanupRound(ROUND, 100));

    assertThat(deleted).isEqualTo(2);
    assertThat(exists(losing)).isFalse();
    assertThat(exists(guestRecord)).isFalse();
    assertThat(exists(fourth)).isTrue();
    assertThat(exists(bought)).isTrue();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lotto_record", Long.class)).isEqualTo(3);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM recommendation_item", Long.class))
        .isZero();
    assertThat(jdbc.queryForObject("SELECT value_count FROM usage_aggregate", Long.class))
        .isEqualTo(5);
  }

  @Test
  @DisplayName("보관 기간 전, 정리 보류, 알림 계획 미완료, 집계 미확정, 정정 보호 중이면 지우지 않는다")
  void guards() {
    long losing = record(member, LOSING, false);
    publishAndEvaluate();

    assertThat(cleanup(Instant.parse("2026-12-01T00:00:00Z"))).isZero();
    jdbc.update("UPDATE lotto_round SET cleanup_held = TRUE");
    assertThat(cleanup(AFTER_RETENTION)).isZero();
    jdbc.update("UPDATE lotto_round SET cleanup_held = FALSE, notification_plan_closed_at = NULL");
    assertThat(cleanup(AFTER_RETENTION)).isZero();
    jdbc.update("UPDATE lotto_round SET notification_plan_closed_at = NOW(6)");
    jdbc.update("UPDATE usage_aggregate SET state = 'BUILDING'");
    assertThat(cleanup(AFTER_RETENTION)).isZero();
    jdbc.update("UPDATE usage_aggregate SET state = 'VERIFIED'");
    jdbc.update("UPDATE lotto_record SET correction_protected_until = '2027-01-01 00:00:00'");
    assertThat(cleanup(AFTER_RETENTION)).isZero();
    assertThat(exists(losing)).isTrue();
  }

  private int cleanup(Instant now) {
    Integer deleted = tx.execute(s -> cleaner(now).cleanupRound(ROUND, 100));
    return deleted == null ? 0 : deleted;
  }

  private RecordRetentionCleaner cleaner(Instant now) {
    return new RecordRetentionCleaner(jdbc, Clock.fixed(now, ZoneOffset.UTC));
  }

  private void publishAndEvaluate() {
    Clock clock = Clock.fixed(Instant.parse("2026-11-14T12:00:00Z"), ZoneOffset.UTC);
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
    long resultId = jdbc.queryForObject("SELECT published_result_id FROM lotto_round", Long.class);
    tx.execute(s -> new EvaluationStore(jdbc).evaluateBatch(resultId, 0, 100));
  }

  private long record(long subject, String key, boolean purchased) {
    jdbc.update(
        "INSERT INTO lotto_record (public_id, owner_subject_id, round_no, numbers_key, recommended_at,"
            + " purchased, purchased_at) VALUES (UUID_TO_BIN(UUID()), ?, ?, ?, NOW(6), ?, "
            + (purchased ? "NOW(6)" : "NULL")
            + ")",
        subject,
        ROUND,
        key,
        purchased);
    return jdbc.queryForObject("SELECT MAX(id) FROM lotto_record", Long.class);
  }

  private void recommendationItem(long recordId) {
    jdbc.update(
        "INSERT INTO recommendation_request (public_id, subject_id, round_no, key_digest, request_hash,"
            + " accepted_at, policy_version, generation_mode, filter_version, response_expires_at)"
            + " VALUES (UUID_TO_BIN(UUID()), ?, ?, ?, ?, NOW(6), 1, 'BASIC', 'FILTER_V1', NOW(6))",
        member,
        ROUND,
        new byte[32],
        new byte[32]);
    long request = jdbc.queryForObject("SELECT MAX(id) FROM recommendation_request", Long.class);
    jdbc.update(
        "INSERT INTO recommendation_item (request_id, ordinal, record_id, numbers_key) VALUES (?, 1, ?, ?)",
        request,
        recordId,
        LOSING);
  }

  private boolean exists(long id) {
    return jdbc.queryForObject("SELECT COUNT(*) FROM lotto_record WHERE id = ?", Long.class, id)
        == 1;
  }
}
