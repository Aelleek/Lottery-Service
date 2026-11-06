package com.lottery.lottery_service.result.evaluate;

import static org.assertj.core.api.Assertions.assertThat;

import com.lottery.lottery_service.account.GuestCredentialStore;
import com.lottery.lottery_service.common.job.JobProperties;
import com.lottery.lottery_service.common.job.JobStore;
import com.lottery.lottery_service.common.number.LottoNumber;
import com.lottery.lottery_service.common.number.LottoNumbers;
import com.lottery.lottery_service.result.domain.PrizeRank;
import com.lottery.lottery_service.result.domain.WinningNumbers;
import com.lottery.lottery_service.result.publish.PrizeProperties;
import com.lottery.lottery_service.result.publish.ResultPublisher;
import com.lottery.lottery_service.result.source.OfficialDraw;
import com.lottery.lottery_service.round.RoundStore;
import com.lottery.lottery_service.round.domain.RoundSchedule;
import com.lottery.lottery_service.support.MySqlContainerSupport;
import com.lottery.lottery_service.support.TestSecrets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 판정을 MySQL에서 확인한다. */
class EvaluationStoreMySqlTest extends MySqlContainerSupport {

  private static final int ROUND = 1200;
  private static final WinningNumbers WIN =
      new WinningNumbers(LottoNumbers.of(1, 2, 3, 4, 5, 6), LottoNumber.of(7));

  private static DataSource dataSource;

  private JdbcTemplate jdbc;
  private TransactionTemplate tx;
  private ResultPublisher publisher;
  private EvaluationStore store;
  private long subject;
  private final List<Long> ids = new ArrayList<>();

  @BeforeAll
  static void migrate() throws Exception {
    dataSource = migratedDatabase("evaluation_store");
  }

  @BeforeEach
  void setUp() {
    jdbc = new JdbcTemplate(dataSource);
    for (String t :
        List.of("winning_evaluation", "job", "lotto_record", "guest_credential", "actor_subject")) {
      jdbc.update("DELETE FROM " + t);
    }
    jdbc.update("UPDATE lotto_round SET published_result_id = NULL");
    jdbc.update("DELETE FROM draw_prize");
    jdbc.update("DELETE FROM draw_result");
    jdbc.update("DELETE FROM lotto_round");
    DataSourceTransactionManager tm = new DataSourceTransactionManager(dataSource);
    tx = new TransactionTemplate(tm);
    RoundStore rounds = new RoundStore(jdbc, tm);
    Instant now = rounds.dbNow();
    rounds.register(
        new RoundSchedule(
            ROUND, now.minus(Duration.ofDays(7)), now.minusSeconds(3600), now.minusSeconds(60), 1));
    Clock clock = Clock.fixed(Instant.parse("2026-11-14T12:00:00Z"), ZoneOffset.UTC);
    publisher =
        new ResultPublisher(
            jdbc,
            tm,
            new JobStore(jdbc, tm, clock, new JobProperties(null, null)),
            new PrizeProperties(null, null),
            clock);
    store = new EvaluationStore(jdbc);
    subject = new GuestCredentialStore(jdbc, tm, clock, TestSecrets.keys()).issue().subjectId();
    ids.clear();
    for (LottoNumbers n :
        List.of(
            LottoNumbers.of(1, 2, 3, 4, 5, 6),
            LottoNumbers.of(1, 2, 3, 4, 5, 7),
            LottoNumbers.of(1, 2, 3, 4, 5, 8),
            LottoNumbers.of(1, 2, 3, 4, 8, 9),
            LottoNumbers.of(1, 2, 3, 8, 9, 10),
            LottoNumbers.of(1, 2, 7, 8, 9, 10))) {
      jdbc.update(
          "INSERT INTO lotto_record (public_id, owner_subject_id, round_no, numbers_key)"
              + " VALUES (UUID_TO_BIN(UUID()), ?, ?, ?)",
          subject,
          ROUND,
          n.key());
      ids.add(jdbc.queryForObject("SELECT MAX(id) FROM lotto_record", Long.class));
    }
  }

  @Test
  @DisplayName("묶음으로 1~5등과 낙첨을 판정하고 보너스는 2등에만 쓴다. 다시 돌려도 판정 행이 늘지 않는다(T23)")
  void batchEvaluation() {
    long resultId = publish(WIN);
    assertThat(store.latest(ids.get(0))).isEmpty();

    EvaluationBatch first = tx.execute(s -> store.evaluateBatch(resultId, 0, 4));
    EvaluationBatch second =
        tx.execute(s -> store.evaluateBatch(resultId, first.lastRecordId(), 4));

    assertThat(first.finished()).isFalse();
    assertThat(second.finished()).isTrue();
    assertThat(ids.stream().map(id -> store.latest(id).orElseThrow()).toList())
        .containsExactly(
            PrizeRank.FIRST,
            PrizeRank.SECOND,
            PrizeRank.THIRD,
            PrizeRank.FOURTH,
            PrizeRank.FIFTH,
            PrizeRank.NONE);
    tx.execute(s -> store.evaluateBatch(resultId, 0, 100));
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM winning_evaluation", Long.class))
        .isEqualTo(6);
    assertThat(
            jdbc.queryForObject(
                "SELECT matched_count FROM winning_evaluation WHERE record_id = ?",
                Integer.class,
                ids.get(1)))
        .isEqualTo(5);
  }

  @Test
  @DisplayName("기록 버전이 바뀌면 다시 판정하기 전까지 확인 중이다")
  void recordChangeNeedsReevaluation() {
    long resultId = publish(WIN);
    tx.execute(s -> store.evaluateBatch(resultId, 0, 100));
    jdbc.update("UPDATE lotto_record SET version = version + 1 WHERE id = ?", ids.get(0));

    assertThat(store.latest(ids.get(0))).isEmpty();
    Optional<PrizeRank> reevaluated = tx.execute(s -> store.evaluateRecord(ids.get(0)));
    assertThat(reevaluated).contains(PrizeRank.FIRST);
    assertThat(store.latest(ids.get(0))).contains(PrizeRank.FIRST);
  }

  @Test
  @DisplayName("정정 승인 뒤에는 이전 게시본의 등수를 보여 주지 않고, 이전 게시본의 판정 묶음은 멈춘다")
  void correctionHidesOldEvaluation() {
    long first = publish(WIN);
    tx.execute(s -> store.evaluateBatch(first, 0, 100));
    publisher.receive(
        new OfficialDraw(
            ROUND,
            new WinningNumbers(LottoNumbers.of(1, 2, 3, 4, 5, 45), LottoNumber.of(6)),
            null,
            null,
            new byte[32]));
    long corrected =
        jdbc.queryForObject("SELECT id FROM draw_result WHERE state = 'QUARANTINED'", Long.class);
    publisher.approve(corrected, 1);

    assertThat(store.latest(ids.get(0))).isEmpty();
    assertThat(tx.execute(s -> store.evaluateBatch(first, 0, 100)).superseded()).isTrue();
    tx.execute(s -> store.evaluateBatch(corrected, 0, 100));
    assertThat(store.latest(ids.get(0))).contains(PrizeRank.SECOND);
  }

  private long publish(WinningNumbers winning) {
    publisher.receive(new OfficialDraw(ROUND, winning, null, null, new byte[32]));
    return jdbc.queryForObject(
        "SELECT published_result_id FROM lotto_round WHERE round_no = ?", Long.class, ROUND);
  }
}
