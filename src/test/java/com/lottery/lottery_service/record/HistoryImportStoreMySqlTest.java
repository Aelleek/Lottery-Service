package com.lottery.lottery_service.record;

import static org.assertj.core.api.Assertions.assertThat;

import com.lottery.lottery_service.account.AccountStore;
import com.lottery.lottery_service.account.GuestCredentialStore;
import com.lottery.lottery_service.account.SubjectClaimStore;
import com.lottery.lottery_service.auth.OAuthProvider;
import com.lottery.lottery_service.common.job.JobProperties;
import com.lottery.lottery_service.common.job.JobRunner;
import com.lottery.lottery_service.common.job.JobState;
import com.lottery.lottery_service.common.job.JobStore;
import com.lottery.lottery_service.common.number.LottoNumbers;
import com.lottery.lottery_service.round.RoundStore;
import com.lottery.lottery_service.round.domain.RoundSchedule;
import com.lottery.lottery_service.support.MySqlContainerSupport;
import com.lottery.lottery_service.support.TestSecrets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 비회원 기록 가져오기를 MySQL에서 확인한다. */
class HistoryImportStoreMySqlTest extends MySqlContainerSupport {

  private static final int OPEN = 1200;
  private static final int CLOSED = 1199;
  private static final LocalDateTime RECOMMENDED = LocalDateTime.parse("2026-11-01T03:00:00");
  private static final String A = LottoNumbers.of(1, 2, 3, 4, 5, 6).key();
  private static final String B = LottoNumbers.of(1, 2, 3, 4, 5, 7).key();
  private static final String C = LottoNumbers.of(1, 2, 3, 4, 5, 8).key();
  private static final String D = LottoNumbers.of(1, 2, 3, 4, 5, 9).key();
  private static final String E = LottoNumbers.of(1, 2, 3, 4, 5, 10).key();

  private static DataSource dataSource;

  private JdbcTemplate jdbc;
  private TransactionTemplate tx;
  private HistoryImportStore store;
  private long guest;
  private long member;
  private long claim;

  @BeforeAll
  static void migrate() throws Exception {
    dataSource = migratedDatabase("history_import");
  }

  @BeforeEach
  void setUp() {
    jdbc = new JdbcTemplate(dataSource);
    for (String t :
        List.of(
            "job",
            "history_import_item",
            "lotto_record",
            "subject_claim",
            "oauth_account",
            "guest_credential",
            "actor_subject",
            "member")) {
      jdbc.update("DELETE FROM " + t);
    }
    jdbc.update("UPDATE lotto_round SET published_result_id = NULL");
    jdbc.update("DELETE FROM draw_result");
    jdbc.update("DELETE FROM lotto_round");
    DataSourceTransactionManager tm = new DataSourceTransactionManager(dataSource);
    tx = new TransactionTemplate(tm);
    RoundStore rounds = new RoundStore(jdbc, tm);
    Instant now = rounds.dbNow();
    rounds.register(
        new RoundSchedule(
            CLOSED,
            now.minus(Duration.ofDays(8)),
            now.minus(Duration.ofDays(1)),
            now.minus(Duration.ofDays(1)),
            1));
    rounds.register(
        new RoundSchedule(
            OPEN,
            now.minus(Duration.ofDays(1)),
            now.plus(Duration.ofDays(6)),
            now.plus(Duration.ofDays(6)),
            1));
    jdbc.update(
        "INSERT INTO draw_result (public_id, round_no, revision, numbers_key, bonus_number,"
            + " semantic_hash, source_hash, state, fetched_at, published_at) VALUES"
            + " (UUID_TO_BIN(UUID()), ?, 1, '10 20 30 40 41 42', 7, ?, ?, 'VERIFIED', NOW(6), NOW(6))",
        CLOSED,
        new byte[32],
        new byte[32]);
    jdbc.update(
        "UPDATE lotto_round SET published_result_id = (SELECT id FROM draw_result) WHERE round_no = ?",
        CLOSED);
    Clock clock = Clock.fixed(Instant.parse("2026-11-08T01:00:00Z"), ZoneOffset.UTC);
    guest = new GuestCredentialStore(jdbc, tm, clock, TestSecrets.keys()).issue().subjectId();
    member = new AccountStore(jdbc, tm, clock).login(OAuthProvider.KAKAO, "m").subjectId();
    store =
        new HistoryImportStore(jdbc, new JobStore(jdbc, tm, clock, new JobProperties(null, null)));

    guestRecord(OPEN, A, false);
    guestRecord(OPEN, B, false);
    guestRecord(CLOSED, C, false);
    guestRecord(OPEN, D, false);
    guestRecord(OPEN, E, true);
    memberRecord(OPEN, B, false);
    memberRecord(CLOSED, C, false);
    memberRecord(OPEN, D, true);
    new SubjectClaimStore(jdbc, tm, clock).claim(guest, member);
    claim = jdbc.queryForObject("SELECT id FROM subject_claim", Long.class);
  }

  @Test
  @DisplayName("항목마다 CREATED·MERGED·건너뜀 결과를 남기고, 마감 뒤 회차는 추천 표시를 붙이지 않는다(T51, T53 일부)")
  void importOutcomes() {
    ImportBatch batch = tx.execute(s -> store.importBatch(claim, 0, 100));

    assertThat(batch.finished()).isTrue();
    assertThat(batch.roundsAfterSalesClose()).containsExactly(CLOSED);
    assertThat(outcomes())
        .containsEntry(A, "CREATED")
        .containsEntry(B, "MERGED")
        .containsEntry(C, "MERGED")
        .containsEntry(D, "SKIPPED_TARGET_DELETED")
        .containsEntry(E, "SKIPPED_SOURCE_EXPIRED");

    Map<String, Object> created = memberRow(OPEN, A);
    assertThat(created.get("recommended_at")).isEqualTo(RECOMMENDED);
    assertThat((LocalDateTime) created.get("created_at")).isAfter(RECOMMENDED);
    assertThat(created.get("purchased")).isEqualTo(false);
    assertThat(memberRow(OPEN, B).get("recommended_at")).isEqualTo(RECOMMENDED);
    assertThat(memberRow(CLOSED, C).get("recommended_at")).isNull();
    assertThat(memberRow(OPEN, D).get("deleted_at")).isNotNull();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM job", Long.class)).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT imported_at IS NOT NULL FROM subject_claim WHERE id = ?",
                Boolean.class,
                claim))
        .isTrue();
  }

  @Test
  @DisplayName("묶음으로 나눠도 결과는 같고, 끝난 뒤 다시 실행해도 아무것도 늘지 않는다")
  void batchesAndRerun() {
    long last = 0;
    int batches = 0;
    ImportBatch batch;
    do {
      long from = last;
      batch = tx.execute(s -> store.importBatch(claim, from, 2));
      last = batch.lastSourceId();
      batches++;
    } while (!batch.finished());

    assertThat(batches).isEqualTo(3);
    assertThat(outcomes()).hasSize(5);
    long records = jdbc.queryForObject("SELECT COUNT(*) FROM lotto_record", Long.class);

    ImportBatch again = tx.execute(s -> store.importBatch(claim, 0, 100));
    assertThat(again.processed()).isZero();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lotto_record", Long.class))
        .isEqualTo(records);
  }

  @Test
  @DisplayName("가져오기 job은 묶음마다 진행 위치를 남기며 끝까지 가져오고, 같은 귀속은 한 번만 등록된다")
  void importJob() {
    DataSourceTransactionManager tm = new DataSourceTransactionManager(dataSource);
    Clock clock = Clock.fixed(Instant.parse("2026-11-08T01:00:00Z"), ZoneOffset.UTC);
    JobStore jobs = new JobStore(jdbc, tm, clock, new JobProperties(null, null));
    HistoryImportJobHandler handler = new HistoryImportJobHandler(store, jobs, clock);

    assertThat(handler.start(claim)).isTrue();
    assertThat(handler.start(claim)).isFalse();
    assertThat(new JobRunner(jobs, List.of(handler), tm, clock).runOnce())
        .contains(JobState.SUCCEEDED);
    assertThat(outcomes()).hasSize(5);
  }

  private void guestRecord(int round, String key, boolean expired) {
    jdbc.update(
        "INSERT INTO lotto_record (public_id, owner_subject_id, round_no, numbers_key,"
            + " recommended_at, created_at, expires_at) VALUES (UUID_TO_BIN(UUID()), ?, ?, ?, ?, ?, "
            + (expired ? "NOW(6) - INTERVAL 1 SECOND" : "NOW(6) + INTERVAL 30 DAY")
            + ")",
        guest,
        round,
        key,
        RECOMMENDED,
        RECOMMENDED);
  }

  private void memberRecord(int round, String key, boolean deleted) {
    jdbc.update(
        "INSERT INTO lotto_record (public_id, owner_subject_id, round_no, numbers_key, purchased,"
            + " purchased_at, deleted_at) VALUES (UUID_TO_BIN(UUID()), ?, ?, ?, TRUE, NOW(6), "
            + (deleted ? "NOW(6)" : "NULL")
            + ")",
        member,
        round,
        key);
  }

  private Map<String, String> outcomes() {
    Map<String, String> result = new TreeMap<>();
    jdbc.queryForList(
            "SELECT s.numbers_key, h.outcome FROM history_import_item h"
                + " JOIN lotto_record s ON s.id = h.source_record_id")
        .forEach(r -> result.put((String) r.get("numbers_key"), (String) r.get("outcome")));
    return result;
  }

  private Map<String, Object> memberRow(int round, String key) {
    return jdbc.queryForMap(
        "SELECT recommended_at, created_at, purchased, deleted_at FROM lotto_record"
            + " WHERE owner_subject_id = ? AND round_no = ? AND numbers_key = ?",
        member,
        round,
        key);
  }
}
