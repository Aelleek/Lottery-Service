package com.lottery.lottery_service.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.lottery.lottery_service.account.AccountStore;
import com.lottery.lottery_service.account.GuestCredentialStore;
import com.lottery.lottery_service.auth.OAuthProvider;
import com.lottery.lottery_service.common.job.JobProperties;
import com.lottery.lottery_service.common.job.JobStore;
import com.lottery.lottery_service.common.number.LottoNumber;
import com.lottery.lottery_service.common.number.LottoNumbers;
import com.lottery.lottery_service.result.domain.PrizeRank;
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
import java.util.Map;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 기본 알림 대상 규칙(05 5절)을 MySQL에서 확인한다. */
class NotificationTargetQueryMySqlTest extends MySqlContainerSupport {

  private static final int ROUND = 1200;
  private static final String BEFORE = "2026-11-10 10:00:00";
  private static final String AFTER = "2026-11-14 12:00:00";
  private static final String THIRD = LottoNumbers.of(1, 2, 3, 4, 5, 8).key();
  private static final String FOURTH = LottoNumbers.of(1, 2, 3, 4, 8, 9).key();
  private static final String FIRST = LottoNumbers.of(1, 2, 3, 4, 5, 6).key();

  private static DataSource dataSource;

  private JdbcTemplate jdbc;
  private DataSourceTransactionManager tm;
  private Clock clock;

  @BeforeAll
  static void migrate() throws Exception {
    dataSource = migratedDatabase("notification_target");
  }

  @BeforeEach
  void setUp() {
    jdbc = new JdbcTemplate(dataSource);
    for (String t :
        List.of(
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
            + " '2026-11-14 11:00:00', '2026-11-14 11:35:00', 1, 'SEALED', '2026-11-14 11:00:01',"
            + " '2026-11-14 11:00:00')",
        ROUND);
    tm = new DataSourceTransactionManager(dataSource);
    clock = Clock.fixed(Instant.parse("2026-11-14T12:00:00Z"), ZoneOffset.UTC);
  }

  @Test
  @DisplayName("마감 전 구매 1~5등, 추천 1~3등만 대상이고 취소·늦은 등록·되살림·비회원은 빠진다(T15, T16, T26, T50, T52)")
  void targetRules() {
    long m1 = member("m1");
    record(m1, FOURTH, BEFORE, BEFORE, null);
    long m2 = member("m2");
    record(m2, FOURTH, BEFORE, null, null);
    record(m2, THIRD, BEFORE, null, BEFORE);
    long m3 = member("m3");
    record(m3, FOURTH, AFTER, AFTER, null);
    member("m4");
    long m5 = member("m5");
    record(m5, FOURTH, AFTER, AFTER, null);
    long m6 = member("m6");
    record(m6, THIRD, BEFORE, AFTER, BEFORE);
    record(m6, FOURTH, BEFORE, AFTER, BEFORE);
    long guest = new GuestCredentialStore(jdbc, tm, clock, TestSecrets.keys()).issue().subjectId();
    record(guest, FIRST, BEFORE, null, BEFORE);
    publishAndEvaluate();

    Map<Long, List<NotificationTarget.Item>> targets =
        new NotificationTargetQuery(jdbc)
            .targets(ROUND).stream()
                .collect(
                    Collectors.toMap(NotificationTarget::memberId, NotificationTarget::records));

    assertThat(targets).containsOnlyKeys(memberId(m1), memberId(m2), memberId(m6));
    assertThat(targets.get(memberId(m1)))
        .extracting(NotificationTarget.Item::rank, NotificationTarget.Item::purchased)
        .containsExactly(tuple(PrizeRank.FOURTH, true));
    assertThat(targets.get(memberId(m2)))
        .extracting(NotificationTarget.Item::rank)
        .containsExactly(PrizeRank.THIRD);
    assertThat(targets.get(memberId(m6)))
        .extracting(NotificationTarget.Item::rank, NotificationTarget.Item::purchased)
        .containsExactly(tuple(PrizeRank.THIRD, false));
  }

  @Test
  @DisplayName("봉인 전 회차는 대상을 정하지 않는다")
  void notSealed() {
    long m1 = member("m1");
    record(m1, FOURTH, BEFORE, BEFORE, null);
    publishAndEvaluate();
    jdbc.update("UPDATE lotto_round SET cutoff_state = 'OPEN', cutoff_close_at = NULL");

    assertThat(new NotificationTargetQuery(jdbc).targets(ROUND)).isEmpty();
  }

  private void publishAndEvaluate() {
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
    new TransactionTemplate(tm)
        .execute(s -> new EvaluationStore(jdbc).evaluateBatch(resultId, 0, 100));
  }

  private long member(String id) {
    return new AccountStore(jdbc, tm, clock).login(OAuthProvider.KAKAO, id).subjectId();
  }

  private long memberId(long subject) {
    return jdbc.queryForObject(
        "SELECT member_id FROM actor_subject WHERE id = ?", Long.class, subject);
  }

  private void record(
      long subject, String key, String createdAt, String purchasedAt, String recommendedAt) {
    jdbc.update(
        "INSERT INTO lotto_record (public_id, owner_subject_id, round_no, numbers_key, created_at,"
            + " purchased, purchased_at, recommended_at) VALUES (UUID_TO_BIN(UUID()), ?, ?, ?, ?, ?, ?, ?)",
        subject,
        ROUND,
        key,
        createdAt,
        purchasedAt != null,
        purchasedAt,
        recommendedAt);
  }
}
