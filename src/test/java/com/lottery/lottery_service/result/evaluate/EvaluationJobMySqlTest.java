package com.lottery.lottery_service.result.evaluate;

import static org.assertj.core.api.Assertions.assertThat;

import com.lottery.lottery_service.account.GuestCredentialStore;
import com.lottery.lottery_service.common.job.JobHandler;
import com.lottery.lottery_service.common.job.JobLease;
import com.lottery.lottery_service.common.job.JobProperties;
import com.lottery.lottery_service.common.job.JobRunner;
import com.lottery.lottery_service.common.job.JobState;
import com.lottery.lottery_service.common.job.JobStep;
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

/** 판정 job을 실행기로 끝까지 돌리는 흐름을 MySQL에서 확인한다. */
class EvaluationJobMySqlTest extends MySqlContainerSupport {

  private static final int ROUND = 1200;

  private static DataSource dataSource;

  private final MutableClock clock = new MutableClock(Instant.parse("2026-11-14T12:00:00Z"));
  private JdbcTemplate jdbc;
  private DataSourceTransactionManager tm;
  private JobStore jobs;
  private ResultPublisher publisher;
  private EvaluationStore evaluations;
  private long subject;

  @BeforeAll
  static void migrate() throws Exception {
    dataSource = migratedDatabase("evaluation_job");
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
    tm = new DataSourceTransactionManager(dataSource);
    RoundStore rounds = new RoundStore(jdbc, tm);
    Instant now = rounds.dbNow();
    rounds.register(
        new RoundSchedule(
            ROUND, now.minus(Duration.ofDays(7)), now.minusSeconds(3600), now.minusSeconds(60), 1));
    jobs = new JobStore(jdbc, tm, clock, new JobProperties(5, Duration.ofMinutes(5)));
    publisher = new ResultPublisher(jdbc, tm, jobs, new PrizeProperties(null, null), clock);
    evaluations = new EvaluationStore(jdbc);
    subject = new GuestCredentialStore(jdbc, tm, clock, TestSecrets.keys()).issue().subjectId();
    for (int i = 0; i < 5; i++) {
      jdbc.update(
          "INSERT INTO lotto_record (public_id, owner_subject_id, round_no, numbers_key)"
              + " VALUES (UUID_TO_BIN(UUID()), ?, ?, ?)",
          subject,
          ROUND,
          LottoNumbers.of(1, 2, 3, 4, 5, 10 + i).key());
    }
  }

  @Test
  @DisplayName("게시하면 등록된 판정 job이 묶음마다 진행 위치를 남기며 모든 기록을 판정하고 끝난다")
  void evaluationJobRunsToCompletion() {
    publish();

    JobRunner runner = runner(new EvaluateResultJobHandler(evaluations, 2));
    assertThat(runner.runOnce()).contains(JobState.SUCCEEDED);

    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM winning_evaluation", Long.class))
        .isEqualTo(5);
    assertThat(
            jdbc.queryForObject(
                "SELECT cursor_json FROM job WHERE job_type = 'EVALUATE_RESULT'", String.class))
        .contains("lastRecordId");
    assertThat(runner.runOnce()).isEmpty();
  }

  @Test
  @DisplayName("취소 요청된 판정 job은 판정하지 않고 CANCELED로 끝난다")
  void canceledJob() {
    publish();
    jdbc.update("UPDATE job SET cancel_requested = TRUE");

    assertThat(runner(new EvaluateResultJobHandler(evaluations, 2)).runOnce())
        .contains(JobState.CANCELED);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM winning_evaluation", Long.class)).isZero();
  }

  @Test
  @DisplayName("처리기가 실패하면 그 걸음의 쓰기는 rollback되고 RETRY_WAIT로 다음 시도를 기다린다")
  void failureRollsBackStep() {
    publish();
    JobHandler failing =
        new JobHandler() {
          @Override
          public String type() {
            return ResultPublisher.EVALUATE_RESULT_JOB;
          }

          @Override
          public JobStep step(JobLease lease, String cursorJson) {
            evaluations.evaluateBatch(
                jdbc.queryForObject("SELECT published_result_id FROM lotto_round", Long.class),
                0,
                100);
            throw new IllegalStateException("boom");
          }
        };

    assertThat(runner(failing).runOnce()).contains(JobState.RETRY_WAIT);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM winning_evaluation", Long.class)).isZero();
    assertThat(jdbc.queryForObject("SELECT last_error_code FROM job", String.class))
        .isEqualTo("IllegalStateException");
  }

  @Test
  @DisplayName("기록 판정 job은 그 기록을 현재 게시본으로 판정한다")
  void recordJob() {
    publish();
    jdbc.update("DELETE FROM job");
    long recordId = jdbc.queryForObject("SELECT MIN(id) FROM lotto_record", Long.class);
    jobs.enqueue(
        "EVALUATE_RECORD",
        "record:" + recordId + ":v0",
        "{\"recordId\":" + recordId + "}",
        clock.instant());

    assertThat(runner(new EvaluateRecordJobHandler(evaluations)).runOnce())
        .contains(JobState.SUCCEEDED);
    assertThat(evaluations.latest(recordId)).contains(PrizeRank.SECOND);
  }

  @Test
  @DisplayName("미평가 대조는 판정이 빠진 기록마다 판정 job을 한 번만 등록하고, 판정된 기록과 지운 기록은 건너뛴다")
  void reconcileMissingEvaluations() {
    publish();
    jdbc.update("DELETE FROM job");
    long first = jdbc.queryForObject("SELECT MIN(id) FROM lotto_record", Long.class);
    evaluations.evaluateRecord(first);
    jdbc.update("UPDATE lotto_record SET deleted_at = NOW(6) WHERE id = ?", first + 1);
    EvaluationReconciler reconciler = new EvaluationReconciler(jdbc, jobs, clock);

    assertThat(reconciler.enqueueMissing(100)).isEqualTo(3);
    assertThat(reconciler.enqueueMissing(100)).isZero();
    jdbc.update("UPDATE lotto_record SET version = version + 1 WHERE id = ?", first);
    assertThat(reconciler.enqueueMissing(100)).isEqualTo(1);
  }

  private void publish() {
    publisher.receive(
        new OfficialDraw(
            ROUND,
            new WinningNumbers(LottoNumbers.of(1, 2, 3, 4, 5, 6), LottoNumber.of(10)),
            null,
            null,
            new byte[32]));
  }

  private JobRunner runner(JobHandler handler) {
    return new JobRunner(jobs, List.of(handler), tm, clock);
  }
}
