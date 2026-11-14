package com.lottery.lottery_service.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.lottery.lottery_service.account.AccountStore;
import com.lottery.lottery_service.admin.AdminRole;
import com.lottery.lottery_service.auth.OAuthProvider;
import com.lottery.lottery_service.common.error.BusinessException;
import com.lottery.lottery_service.common.error.ErrorCode;
import com.lottery.lottery_service.common.job.JobProperties;
import com.lottery.lottery_service.common.job.JobRunner;
import com.lottery.lottery_service.common.job.JobState;
import com.lottery.lottery_service.common.job.JobStore;
import com.lottery.lottery_service.common.number.LottoNumber;
import com.lottery.lottery_service.common.number.LottoNumbers;
import com.lottery.lottery_service.notification.budget.BudgetProperties;
import com.lottery.lottery_service.notification.budget.MessageBudgetStore;
import com.lottery.lottery_service.record.HousekeepingCleaner;
import com.lottery.lottery_service.result.domain.WinningNumbers;
import com.lottery.lottery_service.result.evaluate.EvaluateResultJobHandler;
import com.lottery.lottery_service.result.evaluate.EvaluationStore;
import com.lottery.lottery_service.result.publish.PrizeProperties;
import com.lottery.lottery_service.result.publish.ResultPublisher;
import com.lottery.lottery_service.result.source.OfficialDraw;
import com.lottery.lottery_service.result.source.ParsedDraw;
import com.lottery.lottery_service.result.source.ResultCollectJobHandler;
import com.lottery.lottery_service.result.source.ResultCollector;
import com.lottery.lottery_service.round.domain.RoundSchedule;
import com.lottery.lottery_service.support.MutableClock;
import com.lottery.lottery_service.support.MySqlContainerSupport;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 결과 알림 메시지 상태와 비용을 MySQL에서 확인한다. */
class ResultMessageStoreMySqlTest extends MySqlContainerSupport {

  private static final int ROUND = 1200;

  private static DataSource dataSource;

  private final MutableClock clock = new MutableClock(Instant.parse("2026-11-14T12:30:00Z"));
  private JdbcTemplate jdbc;
  private TransactionTemplate tx;
  private ResultMessageStore store;
  private MessageBudgetStore budget;
  private long fourthSubject;
  private long fourthMember;
  private long thirdMember;

  @BeforeAll
  static void migrate() throws Exception {
    dataSource = migratedDatabase("result_message");
  }

  @BeforeEach
  void setUp() {
    jdbc = new JdbcTemplate(dataSource);
    for (String t :
        List.of(
            "delivery_message",
            "message_budget",
            "member_contact",
            "consent_event",
            "winning_evaluation",
            "job",
            "lotto_record",
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
        "INSERT INTO lotto_round (round_no, sales_open_at, sales_close_at, draw_at, schedule_version,"
            + " cutoff_state, cutoff_sealed_at, cutoff_close_at) VALUES (?, '2026-11-07 21:00:00',"
            + " '2026-11-14 11:00:00', '2026-11-14 11:35:00', 1, 'SEALED', '2026-11-14 11:00:00',"
            + " '2026-11-14 11:00:00')",
        ROUND);
    DataSourceTransactionManager tm = new DataSourceTransactionManager(dataSource);
    tx = new TransactionTemplate(tm);
    AccountStore accounts = new AccountStore(jdbc, tm, clock);
    fourthSubject = accounts.login(OAuthProvider.KAKAO, "fourth").subjectId();
    long thirdSubject = accounts.login(OAuthProvider.KAKAO, "third").subjectId();
    fourthMember = memberOf(fourthSubject);
    thirdMember = memberOf(thirdSubject);
    record(fourthSubject, LottoNumbers.of(1, 2, 3, 4, 8, 9).key(), true, false);
    record(thirdSubject, LottoNumbers.of(1, 2, 3, 4, 5, 8).key(), false, true);
    consent(fourthMember);
    consent(thirdMember);
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
    budget = new MessageBudgetStore(jdbc, new BudgetProperties(300_000L, 90));
    store = new ResultMessageStore(jdbc, new NotificationTargetQuery(jdbc), budget, clock);
  }

  @Test
  @DisplayName("대상 회원마다 메시지를 하나만 만들고, 마감 때 4등이던 구매를 발송 전에 취소하면 보내지 않는다(T16)")
  void createAndSuppress() {
    assertThat(count(() -> store.createMissing(ROUND))).isEqualTo(2);
    assertThat(count(() -> store.createMissing(ROUND))).isZero();
    jdbc.update(
        "UPDATE lotto_record SET purchased = FALSE, purchased_at = NULL, version = version + 1"
            + " WHERE owner_subject_id = ?",
        fourthSubject);

    assertThat(inTx(() -> store.recheck(message(fourthMember)))).isFalse();
    assertThat(inTx(() -> store.recheck(message(thirdMember)))).isTrue();
    assertThat(state(fourthMember)).isEqualTo("SUPPRESSED");
  }

  @Test
  @DisplayName("알림 수신을 끈 회원은 보내지 않는다")
  void consentOff() {
    tx.execute(s -> store.createMissing(ROUND));
    jdbc.update(
        "UPDATE member_contact SET notify_enabled = FALSE, consent_event_id = NULL WHERE member_id = ?",
        thirdMember);

    assertThat(inTx(() -> store.recheck(message(thirdMember)))).isFalse();
  }

  @Test
  @DisplayName("접수는 비용 확정, 확정 미접수는 반환 뒤 다시 시도, 접수 여부를 모르면 예약 유지이고 상태는 뒤로 가지 않는다(T18 일부)")
  void outcomes() {
    tx.execute(s -> store.createMissing(ROUND));
    long a = message(fourthMember);
    long b = message(thirdMember);

    assertThat(inTx(() -> store.startSending(a, 143))).isTrue();
    assertThat(inTx(() -> store.startSending(b, 143))).isTrue();
    tx.executeWithoutResult(s -> store.recordOutcome(a, new SendOutcome.Accepted("M-1")));
    tx.executeWithoutResult(s -> store.recordOutcome(b, new SendOutcome.NotAccepted("E-1")));
    tx.executeWithoutResult(s -> store.recordOutcome(a, new SendOutcome.Unknown()));

    assertThat(state(fourthMember)).isEqualTo("ACCEPTED");
    assertThat(state(thirdMember)).isEqualTo("RETRY_WAIT");
    assertThat(budget.matchesMessages(LocalDate.parse("2026-11-01"))).isTrue();
    assertThat(budgetRow()).containsEntry("reserved_units", 0L).containsEntry("spent_units", 143L);

    Map<String, Object> pinned = recipient(b);
    assertThat(pinned.get("recipient_version")).isNotNull();
    assertThat(pinned.get("consent_event_id")).isNotNull();
    assertThat((byte[]) pinned.get("recipient_ciphertext")).containsExactly(1);
    jdbc.update(
        "UPDATE member_contact SET address_ciphertext = ?, version = version + 1 WHERE member_id = ?",
        new byte[] {2},
        thirdMember);
    assertThat(inTx(() -> store.startSending(b, 143))).isTrue();
    assertThat(recipient(b).get("recipient_version")).isEqualTo(pinned.get("recipient_version"));
    assertThat((byte[]) recipient(b).get("recipient_ciphertext")).containsExactly(1);
    tx.executeWithoutResult(s -> store.recordOutcome(b, new SendOutcome.Unknown()));
    assertThat(state(thirdMember)).isEqualTo("UNKNOWN");
    assertThat(budgetRow()).containsEntry("reserved_units", 143L);
    assertThat(inTx(() -> store.markDelivered(a))).isTrue();
    assertThat(inTx(() -> store.markDelivered(a))).isFalse();
    assertThat(state(fourthMember)).isEqualTo("DELIVERED");
  }

  @Test
  @DisplayName("예산이 모자라면 IN_FLIGHT로 가지 않고 기다리며, 마지막 슬롯 실패 때 남은 메시지는 HALTED")
  void budgetShortAndHalt() {
    store =
        new ResultMessageStore(
            jdbc,
            new NotificationTargetQuery(jdbc),
            new MessageBudgetStore(jdbc, new BudgetProperties(100L, 90)),
            clock);
    tx.execute(s -> store.createMissing(ROUND));
    long a = message(fourthMember);

    assertThat(inTx(() -> store.startSending(a, 143))).isFalse();
    assertThat(state(fourthMember)).isEqualTo("PENDING");
    assertThat(count(() -> store.haltRemaining(ROUND))).isEqualTo(2);
    assertThat(store.sendable(ROUND)).isEmpty();
  }

  @Test
  @DisplayName("UNKNOWN은 조회에서 못 찾으면 그대로, 접수가 확인되면 비용 확정, 그 뒤 늦은 결과로 뒤로 가지 않는다")
  void reconcileUnknown() {
    UnknownMessageReconciler reconciler = reconciler();
    long a = unknownMessage(fourthMember);

    assertThat(apply(reconciler, a, new ProviderStatus.NotFound())).isEqualTo("UNKNOWN");
    assertThat(apply(reconciler, a, new ProviderStatus.Accepted("M-9"))).isEqualTo("ACCEPTED");
    assertThat(apply(reconciler, a, new ProviderStatus.Failed("LATE"))).isEqualTo("ACCEPTED");
    assertThat(apply(reconciler, a, new ProviderStatus.Delivered("M-9"))).isEqualTo("DELIVERED");
    assertThat(budgetRow()).containsEntry("reserved_units", 0L).containsEntry("spent_units", 143L);
  }

  @Test
  @DisplayName("대조 순회는 UNKNOWN을 조회해 반영하고, 조회가 예외로 끝난 메시지는 UNKNOWN으로 남기며 다른 메시지를 막지 않는다")
  void sweepUnknown() {
    long a = unknownMessage(fourthMember);
    long b = unknownMessage(thirdMember);
    List<MessageLookup.UnknownMessage> seen = new ArrayList<>();
    Map<Long, ProviderStatus> answers = new HashMap<>();
    answers.put(a, new ProviderStatus.Accepted("M-1"));
    MessageLookup lookup =
        m -> {
          seen.add(m);
          ProviderStatus status = answers.get(m.id());
          if (status == null) {
            throw new IllegalStateException("lookup down");
          }
          return status;
        };
    UnknownMessageSweep sweep = sweep(lookup);

    assertThat(sweep.run(10)).isEqualTo(1);
    assertThat(state(fourthMember)).isEqualTo("ACCEPTED");
    assertThat(state(thirdMember)).isEqualTo("UNKNOWN");
    assertThat(seen).extracting(MessageLookup.UnknownMessage::id).containsExactly(a, b);
    assertThat(seen.get(1).providerRequestKey()).hasSize(16);
    assertThat(seen.get(1).providerMessageId()).isNull();
    assertThat(seen.get(1).lastAttemptAt()).isEqualTo(clock.instant());

    answers.put(b, new ProviderStatus.Failed("REJECTED"));
    assertThat(sweep.run(10)).isEqualTo(1);
    assertThat(state(thirdMember)).isEqualTo("FAILED");
    assertThat(sweep.run(10)).isZero();
    assertThat(seen).hasSize(3);
    assertThat(budgetRow()).containsEntry("reserved_units", 0L).containsEntry("spent_units", 143L);
  }

  @Test
  @DisplayName("대조 순회는 한 번에 정한 건수만 조회하고 다음 번에는 이어서, 끝에 닿으면 처음부터 다시 돈다")
  void sweepRotates() {
    long a = unknownMessage(fourthMember);
    long b = unknownMessage(thirdMember);
    List<Long> seen = new ArrayList<>();
    UnknownMessageSweep sweep =
        sweep(
            m -> {
              seen.add(m.id());
              return new ProviderStatus.NotFound();
            });

    sweep.run(1);
    sweep.run(1);
    sweep.run(1);
    sweep.run(2);

    assertThat(seen).containsExactly(a, b, a, b, a);
    assertThat(state(fourthMember)).isEqualTo("UNKNOWN");
  }

  @Test
  @DisplayName("기본 조회 구현은 찾지 못했다고 답해 대조 기간 안에는 UNKNOWN으로 두고, 기간이 지나면 종결 규칙으로 끝낸다")
  void unavailableLookup() {
    unknownMessage(fourthMember);
    UnknownMessageSweep sweep = sweep(new UnavailableMessageLookup());

    assertThat(sweep.run(UnknownMessageSweep.MAX_LOOKUPS)).isZero();
    assertThat(state(fourthMember)).isEqualTo("UNKNOWN");
    clock.set(clock.instant().plus(UnknownMessageReconciler.RECONCILE_WINDOW));
    assertThat(sweep.run(UnknownMessageSweep.MAX_LOOKUPS)).isEqualTo(1);
    assertThat(state(fourthMember)).isEqualTo("FAILED");
  }

  @Test
  @DisplayName("대조 기간이 지나 한 번에 끝낸 메시지는 회차마다 건수를 담은 운영자 알림 1건으로 묶는다(07 2.1절)")
  void reconcileTimeoutsAlertOncePerRound() {
    unknownMessage(fourthMember);
    unknownMessage(thirdMember);
    jdbc.update("DELETE FROM job WHERE job_type = 'OPERATOR_ALERT'");
    clock.set(clock.instant().plus(UnknownMessageReconciler.RECONCILE_WINDOW));

    assertThat(sweep(new UnavailableMessageLookup()).run(UnknownMessageSweep.MAX_LOOKUPS))
        .isEqualTo(2);

    assertThat(
            jdbc.queryForList(
                "SELECT CAST(payload AS CHAR) FROM job WHERE job_type = 'OPERATOR_ALERT'",
                String.class))
        .containsExactly("{\"kind\": \"MESSAGE_UNRESOLVED\", \"count\": 2, \"roundNo\": 1200}");
  }

  @Test
  @DisplayName("7일 동안 풀리지 않은 UNKNOWN은 FAILED와 비용 확정, 운영자 알림 1건이고 그 뒤 접수로 나와도 다시 보내지 않는다(T46)")
  void unresolvedUnknownFails() {
    UnknownMessageReconciler reconciler = reconciler();
    long a = unknownMessage(fourthMember);
    clock.set(clock.instant().plus(UnknownMessageReconciler.RECONCILE_WINDOW));

    assertThat(apply(reconciler, a, new ProviderStatus.NotFound())).isEqualTo("FAILED");
    assertThat(count(() -> reconciler.alertReconcileTimeouts(List.of(a)))).isEqualTo(1);
    assertThat(apply(reconciler, a, new ProviderStatus.Accepted("M-1"))).isEqualTo("FAILED");
    assertThat(budgetRow()).containsEntry("reserved_units", 0L).containsEntry("spent_units", 143L);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM job WHERE job_type = 'OPERATOR_ALERT'", Long.class))
        .isEqualTo(1);
    assertThat(store.sendable(ROUND)).doesNotContain(a);
  }

  @Test
  @DisplayName("마지막 시도 뒤 24시간 넘게 UNKNOWN이면 서울 날짜마다 한 번 건수와 가장 오래된 시각으로 알린다")
  void longUnknownAlert() {
    UnknownMessageReconciler reconciler = reconciler();
    unknownMessage(fourthMember);
    Instant attempted = clock.instant();
    jdbc.update("DELETE FROM job");

    clock.set(attempted.plus(UnknownMessageReconciler.LONG_UNKNOWN).minusSeconds(1));
    assertThat(tx.<Boolean>execute(s -> reconciler.alertLongUnresolved())).isFalse();
    clock.set(attempted.plus(UnknownMessageReconciler.LONG_UNKNOWN));
    assertThat(tx.<Boolean>execute(s -> reconciler.alertLongUnresolved())).isTrue();
    assertThat(tx.<Boolean>execute(s -> reconciler.alertLongUnresolved())).isFalse();

    assertThat(
            jdbc.queryForObject(
                "SELECT CAST(payload AS CHAR) FROM job WHERE job_type = 'OPERATOR_ALERT'",
                String.class))
        .contains("\"count\": 1")
        .contains("MESSAGE_UNKNOWN_LONG");
  }

  @Test
  @DisplayName("보낼 때 안내 요약(게시본, 등수, 금액)을 남기고 번호는 담지 않는다")
  void storesCommunicatedSummary() {
    tx.execute(s -> store.createMissing(ROUND));
    long a = message(fourthMember);

    inTx(() -> store.startSending(a, 143));

    CommunicatedSummary summary =
        CommunicatedSummary.parse(
            jdbc.queryForObject(
                "SELECT CAST(communicated_summary AS CHAR) FROM delivery_message WHERE id = ?",
                String.class,
                a));
    long resultId = jdbc.queryForObject("SELECT published_result_id FROM lotto_round", Long.class);
    assertThat(summary)
        .isEqualTo(
            new CommunicatedSummary(resultId, List.of(new CommunicatedSummary.Item(4, 50_000L))));
    assertThat(
            jdbc.queryForObject(
                "SELECT CAST(communicated_summary AS CHAR) FROM delivery_message WHERE id = ?",
                String.class,
                a))
        .doesNotContain("numbers");
  }

  @Test
  @DisplayName("정정 판정이 끝나면 접수된 안내와 등수가 달라진 회원 수와 새로 대상이 된 회원 수로 운영자 알림 1건(T17)")
  void correctionImpactAlert() {
    long newSubject =
        new AccountStore(jdbc, new DataSourceTransactionManager(dataSource), clock)
            .login(OAuthProvider.KAKAO, "new")
            .subjectId();
    record(newSubject, LottoNumbers.of(1, 2, 3, 8, 10, 30).key(), false, true);
    long first = jdbc.queryForObject("SELECT published_result_id FROM lotto_round", Long.class);
    tx.execute(s -> new EvaluationStore(jdbc).evaluateBatch(first, 0, 100));
    tx.execute(s -> store.createMissing(ROUND));
    for (long member : List.of(fourthMember, thirdMember)) {
      long id = message(member);
      inTx(() -> store.startSending(id, 143));
      tx.executeWithoutResult(s -> store.recordOutcome(id, new SendOutcome.Accepted("M-" + id)));
    }
    DataSourceTransactionManager tm = new DataSourceTransactionManager(dataSource);
    JobStore jobs = new JobStore(jdbc, tm, clock, new JobProperties(null, null));
    ResultPublisher publisher =
        new ResultPublisher(jdbc, tm, jobs, new PrizeProperties(null, null), clock);
    publisher.receive(
        new OfficialDraw(
            ROUND,
            new WinningNumbers(LottoNumbers.of(1, 2, 3, 4, 8, 10), LottoNumber.of(11)),
            null,
            null,
            new byte[32]));
    long corrected =
        jdbc.queryForObject("SELECT id FROM draw_result WHERE state = 'QUARANTINED'", Long.class);
    publisher.approve(corrected, 1);
    jdbc.update("DELETE FROM job WHERE job_type <> 'EVALUATE_RESULT'");
    jdbc.update("UPDATE job SET state = 'CANCELED' WHERE payload->'$.resultId' = ?", first);

    CorrectionImpact impact =
        new CorrectionImpact(jdbc, new NotificationTargetQuery(jdbc), jobs, clock);
    new JobRunner(
            jobs,
            List.of(new EvaluateResultJobHandler(new EvaluationStore(jdbc), List.of(impact))),
            tm,
            clock)
        .runOnce();

    assertThat(
            jdbc.queryForList(
                "SELECT CAST(payload AS CHAR) FROM job WHERE job_type = 'OPERATOR_ALERT'",
                String.class))
        .containsExactly(
            "{\"kind\": \"RESULT_CORRECTED\", \"changed\": 1, \"roundNo\": 1200,"
                + " \"newlyTargeted\": 1}");
  }

  @Test
  @DisplayName("발송 job 회수 때 IN_FLIGHT는 다시 호출하지 않고 UNKNOWN으로 바꾸며 예약은 유지한다(T27 일부)")
  void inFlightBecomesUnknownOnRecovery() {
    tx.execute(s -> store.createMissing(ROUND));
    long a = message(fourthMember);
    inTx(() -> store.startSending(a, 143));

    assertThat(count(() -> reconciler().markInFlightUnknown(ROUND))).isEqualTo(1);
    assertThat(state(fourthMember)).isEqualTo("UNKNOWN");
    assertThat(budgetRow()).containsEntry("reserved_units", 143L);
    assertThat(store.sendable(ROUND)).doesNotContain(a);
  }

  @Test
  @DisplayName(
      "회수한 job이 결과 알림 슬롯이면 그 회차의 IN_FLIGHT만 UNKNOWN으로 바꾸고, 다른 종류의 job이면 바꾸지 않는다(05 8.3절, T27)")
  void recoveredSlotJobMarksItsRound() {
    tx.execute(s -> store.createMissing(ROUND));
    long a = message(fourthMember);
    inTx(() -> store.startSending(a, 143));
    long slotJob = job("RESULT_SLOT", "{\"roundNo\":" + ROUND + "}");
    long otherJob = job("EVALUATE_RESULT", "{\"roundNo\":" + ROUND + "}");
    long otherRound = job("RESULT_SLOT", "{\"roundNo\":" + (ROUND + 1) + "}");

    assertThat(count(() -> reconciler().markInFlightUnknownForSlotJob(otherJob))).isZero();
    assertThat(count(() -> reconciler().markInFlightUnknownForSlotJob(otherRound))).isZero();
    assertThat(state(fourthMember)).isEqualTo("IN_FLIGHT");
    assertThat(count(() -> reconciler().markInFlightUnknownForSlotJob(slotJob))).isEqualTo(1);
    assertThat(state(fourthMember)).isEqualTo("UNKNOWN");
  }

  private long job(String type, String payload) {
    jdbc.update(
        "INSERT INTO job (public_id, job_type, dedupe_key, state, payload, available_at)"
            + " VALUES (UUID_TO_BIN(UUID()), ?, ?, 'RETRY_WAIT', ?, NOW(6))",
        type,
        UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8),
        payload);
    return jdbc.queryForObject("SELECT MAX(id) FROM job", Long.class);
  }

  @Test
  @DisplayName("슬롯은 대상에게 보내고 모두 접수되면 성공이며 운영자 알림이 없다")
  void slotSuccess() {
    SlotResult result =
        runner(id -> new SendOutcome.Accepted("M-" + id)).run(ROUND, slot(true, false));

    assertThat(result.success()).isTrue();
    assertThat(alerts()).isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM delivery_message WHERE state = 'ACCEPTED'", Long.class))
        .isEqualTo(2);
  }

  @Test
  @DisplayName("한 메시지의 준비 단계 오류가 다른 회원의 발송을 막지 않는다")
  void slotIsolatesPrepareFailure() {
    tx.execute(s -> store.createMissing(ROUND));
    long broken = message(fourthMember);
    ResultMessageStore spied = org.mockito.Mockito.spy(store);
    org.mockito.Mockito.doThrow(new IllegalStateException("boom")).when(spied).recheck(broken);
    DataSourceTransactionManager tm = new DataSourceTransactionManager(dataSource);
    SlotResult result =
        new ResultSlotRunner(
                jdbc,
                spied,
                id -> new SendOutcome.Accepted("M-" + id),
                new JobStore(jdbc, tm, clock, new JobProperties(null, null)),
                tm,
                clock)
            .run(ROUND, slot(true, false));

    assertThat(result.failed()).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM delivery_message WHERE state = 'ACCEPTED'", Long.class))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT state FROM delivery_message WHERE id = ?", String.class, broken))
        .isNotEqualTo("ACCEPTED");
  }

  @Test
  @DisplayName("결과가 없으면 RESULT_NOT_PUBLISHED로 실패하고, 운영자 알림 슬롯에서만 알린다(T36)")
  void slotWithoutResult() {
    jdbc.update("UPDATE lotto_round SET published_result_id = NULL");

    assertThat(runner(id -> new SendOutcome.Accepted("x")).run(ROUND, slot(false, false)).cause())
        .isEqualTo("RESULT_NOT_PUBLISHED");
    assertThat(alerts()).isZero();
    assertThat(runner(id -> new SendOutcome.Accepted("x")).run(ROUND, slot(true, false)).cause())
        .isEqualTo("RESULT_NOT_PUBLISHED");
    assertThat(alerts()).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT JSON_TYPE(JSON_EXTRACT(payload, '$.targets')) FROM job"
                    + " WHERE job_type = 'OPERATOR_ALERT'",
                String.class))
        .isEqualTo("NULL");
  }

  @Test
  @DisplayName("결과가 없고 마지막 수집 시도가 실패했으면 RESULT_FETCH_ERROR로 실패한다(07 2.2절)")
  void slotWhileFetchFailing() {
    jdbc.update("UPDATE lotto_round SET published_result_id = NULL");
    jdbc.update(
        "INSERT INTO job (public_id, job_type, dedupe_key, state, payload, available_at,"
            + " last_error_code, finished_at) VALUES (UUID_TO_BIN(UUID()), 'RESULT_COLLECT', ?,"
            + " 'SUCCEEDED', '{}', NOW(6), 'RESULT_FETCH_ERROR', NOW(6))",
        ("collect:" + ROUND + ":2026-11-14T11:45:00Z").getBytes(StandardCharsets.UTF_8));

    assertThat(runner(id -> new SendOutcome.Accepted("x")).run(ROUND, slot(true, false)).cause())
        .isEqualTo("RESULT_FETCH_ERROR");
    assertThat(
            jdbc.queryForObject(
                "SELECT CONCAT(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.cause')), ' ',"
                    + " JSON_TYPE(JSON_EXTRACT(payload, '$.targets'))) FROM job"
                    + " WHERE job_type = 'OPERATOR_ALERT'",
                String.class))
        .isEqualTo("RESULT_FETCH_ERROR NULL");
  }

  @Test
  @DisplayName("한 건도 보내지 못하고 제공자가 받지 않았으면 PROVIDER_ERROR로 실패한다(07 2.2절)")
  void slotProviderError() {
    SlotResult result =
        runner(id -> new SendOutcome.NotAccepted("E")).run(ROUND, slot(true, false));

    assertThat(result.cause()).isEqualTo("PROVIDER_ERROR");
    assertThat(result.providerFailed()).isEqualTo(result.failed()).isPositive();
    assertThat(
            jdbc.queryForObject(
                "SELECT JSON_UNQUOTE(JSON_EXTRACT(payload, '$.cause')) FROM job"
                    + " WHERE job_type = 'OPERATOR_ALERT'",
                String.class))
        .isEqualTo("PROVIDER_ERROR");
  }

  @Test
  @DisplayName("슬롯 실패 알림의 대상 수는 SUPPRESSED 메시지를 세지 않는다")
  void slotAlertTargetsExcludeSuppressed() {
    tx.execute(s -> store.createMissing(ROUND));
    jdbc.update(
        "UPDATE delivery_message SET state = 'SUPPRESSED' WHERE id = ?", message(thirdMember));

    runner(id -> new SendOutcome.NotAccepted("E")).run(ROUND, slot(true, false));

    assertThat(
            jdbc.queryForObject(
                "SELECT JSON_EXTRACT(payload, '$.targets') FROM job WHERE job_type = 'OPERATOR_ALERT'",
                Integer.class))
        .isEqualTo(1);
  }

  @Test
  @DisplayName("예산이 한 건만 남으면 다음 회원은 예산 차단으로 세고, 운영자 알림에 예산 차단과 제공자 실패 건수를 따로 담는다(2026-11-09 Q16)")
  void budgetBlockedCountedSeparately() {
    store =
        new ResultMessageStore(
            jdbc,
            new NotificationTargetQuery(jdbc),
            new MessageBudgetStore(jdbc, new BudgetProperties(200L, 90)),
            clock);

    SlotResult result = runner(id -> new SendOutcome.Accepted("M")).run(ROUND, slot(true, false));

    assertThat(result.cause()).isEqualTo("BUDGET_EXHAUSTED");
    assertThat(result.failed()).isEqualTo(1);
    assertThat(result.budgetBlocked()).isEqualTo(1);
    assertThat(result.providerFailed()).isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT CONCAT(JSON_EXTRACT(payload, '$.budgetBlocked'), ' ',"
                    + " JSON_EXTRACT(payload, '$.providerFailed')) FROM job"
                    + " WHERE job_type = 'OPERATOR_ALERT'",
                String.class))
        .isEqualTo("1 0");
  }

  @Test
  @DisplayName("일부가 실패하면 PARTIAL_FAILURE, 다음 슬롯은 못 받은 회원에게만 보내고, 마지막 슬롯 실패 때 남은 메시지는 HALTED(T37)")
  void partialFailureThenHalt() {
    SlotResult first =
        runner(
                id ->
                    id == message(fourthMember)
                        ? new SendOutcome.NotAccepted("E")
                        : new SendOutcome.Accepted("M"))
            .run(ROUND, slot(true, false));
    assertThat(first.cause()).isEqualTo("PARTIAL_FAILURE");
    assertThat(first.failed()).isEqualTo(1);
    assertThat(first.providerFailed()).isEqualTo(1);
    assertThat(first.budgetBlocked()).isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT CONCAT(JSON_EXTRACT(payload, '$.targets'), ' ',"
                    + " JSON_UNQUOTE(JSON_EXTRACT(payload, '$.slotAt'))) FROM job"
                    + " WHERE job_type = 'OPERATOR_ALERT'",
                String.class))
        .isEqualTo("2 " + clock.instant());

    List<Long> called = new ArrayList<>();
    runner(
            id -> {
              called.add(id);
              return new SendOutcome.NotAccepted("E");
            })
        .run(ROUND, new ResultSlot(clock.instant().plusSeconds(1800), true, true));

    assertThat(called).containsExactly(message(fourthMember));
    assertThat(state(fourthMember)).isEqualTo("HALTED");
    assertThat(state(thirdMember)).isEqualTo("ACCEPTED");
    assertThat(alerts()).isEqualTo(2);
  }

  @Test
  @DisplayName("제공자 호출이 예외로 끝나면 UNKNOWN으로 두고 다시 보내지 않는다")
  void senderCrashIsUnknown() {
    SlotResult result =
        runner(
                id -> {
                  throw new IllegalStateException("timeout");
                })
            .run(ROUND, slot(false, false));

    assertThat(result.unknown()).isEqualTo(2);
    assertThat(store.sendable(ROUND)).isEmpty();
  }

  @Test
  @DisplayName(
      "수동 다시 보내기는 HALTED 회원에게만 보내고 접수된 회원은 건드리지 않으며, 알림의 접수 수는 같은 때 대조로 접수된 메시지를 세지 않고, 게시 후 7일이 지나면 거부한다(T37, T43)")
  void manualResend() {
    Instant published =
        jdbc.queryForObject("SELECT published_at FROM draw_result", LocalDateTime.class)
            .toInstant(ZoneOffset.UTC);
    clock.set(published.plusSeconds(3600));
    runner(
            id ->
                id == message(fourthMember)
                    ? new SendOutcome.NotAccepted("E")
                    : new SendOutcome.Accepted("M"))
        .run(ROUND, new ResultSlot(clock.instant(), true, true));
    assertThat(state(fourthMember)).isEqualTo("HALTED");
    long reconciling = message(thirdMember);
    jdbc.update("UPDATE delivery_message SET state = 'UNKNOWN' WHERE id = ?", reconciling);
    List<Long> called = new ArrayList<>();
    ManualResendService resend =
        new ManualResendService(
            jdbc,
            runner(
                id -> {
                  called.add(id);
                  jdbc.update(
                      "UPDATE delivery_message SET state = 'ACCEPTED' WHERE id = ?", reconciling);
                  return new SendOutcome.Accepted("M2");
                }),
            new JobStore(
                jdbc,
                new DataSourceTransactionManager(dataSource),
                clock,
                new JobProperties(null, null)),
            new DataSourceTransactionManager(dataSource),
            clock);
    jdbc.update("DELETE FROM job WHERE job_type = 'OPERATOR_ALERT'");

    assertThat(resend.resend(ROUND, AdminRole.OPERATOR).success()).isTrue();
    assertThat(called).containsExactly(message(fourthMember));
    assertThat(state(fourthMember)).isEqualTo("ACCEPTED");
    assertThat(
            jdbc.queryForList(
                "SELECT CAST(payload AS CHAR) FROM job WHERE job_type = 'OPERATOR_ALERT'",
                String.class))
        .containsExactly(
            "{\"kind\": \"MANUAL_RESEND\", \"sent\": 1, \"actor\": \"OPERATOR\", \"failed\": 0,"
                + " \"roundNo\": 1200, \"unknown\": 0}");

    clock.set(published.plus(ResultMessageStore.VALIDITY));
    assertThatThrownBy(() -> resend.resend(ROUND, AdminRole.OPERATOR))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.getCode()).isEqualTo(ErrorCode.DISPATCH_WINDOW_CLOSED));
  }

  @Test
  @DisplayName("알림 계획을 닫은 회차는 정정으로 게시 시각이 바뀌어도 다시 보내지 않고 새 메시지도 만들지 않는다(05 8.7절)")
  void resendRejectedAfterPlanClosed() {
    jdbc.update("UPDATE lotto_round SET notification_plan_closed_at = NOW(6)");
    jdbc.update(
        "UPDATE draw_result d JOIN lotto_round r ON r.published_result_id = d.id"
            + " SET d.published_at = ?",
        LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
    List<Long> called = new ArrayList<>();
    ManualResendService resend =
        new ManualResendService(
            jdbc,
            runner(
                id -> {
                  called.add(id);
                  return new SendOutcome.Accepted("M");
                }),
            new JobStore(
                jdbc,
                new DataSourceTransactionManager(dataSource),
                clock,
                new JobProperties(null, null)),
            new DataSourceTransactionManager(dataSource),
            clock);

    assertThatThrownBy(() -> resend.resend(ROUND, AdminRole.OPERATOR))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.getCode()).isEqualTo(ErrorCode.DISPATCH_WINDOW_CLOSED));
    assertThat(called).isEmpty();
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM delivery_message WHERE purpose = 'RESULT'", Long.class))
        .isZero();
  }

  @Test
  @DisplayName("회차 일정으로 슬롯 job 12개를 한 번만 등록하고, 때가 된 슬롯 job은 실행기 트랜잭션 밖에서 발송한다")
  void slotJobs() {
    DataSourceTransactionManager tm = new DataSourceTransactionManager(dataSource);
    JobStore jobs = new JobStore(jdbc, tm, clock, new JobProperties(null, null));
    RoundJobPlanner planner = new RoundJobPlanner(jobs);
    RoundSchedule schedule =
        new RoundSchedule(
            ROUND,
            Instant.parse("2026-11-07T12:00:00Z"),
            Instant.parse("2026-11-14T11:00:00Z"),
            Instant.parse("2026-11-14T11:35:00Z"),
            1);

    assertThat(planner.planSlots(schedule)).isEqualTo(12);
    assertThat(planner.planSlots(schedule)).isZero();

    jdbc.update("DELETE FROM job WHERE job_type <> 'RESULT_SLOT'");
    ResultSlotJobHandler handler =
        new ResultSlotJobHandler(
            runner(id -> new SendOutcome.Accepted("M")),
            new ResultCollector(r -> new ParsedDraw.NotAnnounced(), null),
            jdbc);
    assertThat(new JobRunner(jobs, List.of(handler), tm, clock).runOnce())
        .contains(JobState.SUCCEEDED);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM delivery_message WHERE state = 'ACCEPTED'", Long.class))
        .isEqualTo(2);

    RoundNotificationStatusQuery.Status status =
        new RoundNotificationStatusQuery(jdbc, clock).status(ROUND);
    assertThat(status.resultState()).isEqualTo("PUBLISHED");
    assertThat(status.slots()).hasSize(1);
    assertThat(status.slots().get(0).outcome()).isEqualTo("SUCCEEDED");
    assertThat(status.slots().get(0).causeCode()).isNull();
    assertThat(status.messageCounts()).containsEntry("ACCEPTED", 2L).containsEntry("UNKNOWN", 0L);
    assertThat(status.autoDispatchClosed()).isFalse();
    assertThatThrownBy(() -> new RoundNotificationStatusQuery(jdbc, clock).status(9999))
        .isInstanceOfSatisfying(
            BusinessException.class, e -> assertThat(e.getCode()).isEqualTo(ErrorCode.NOT_FOUND));
  }

  @Test
  @DisplayName("알림 현황은 실행 중·실패·원인 있는 성공 슬롯을 구분하고, 실행 전·취소 슬롯은 빼며, 격리·미게시 결과와 마감을 보여 준다")
  void notificationStatusBranches() {
    jdbc.update("DELETE FROM job");
    slotJob("a", "SUCCEEDED", "2026-11-14T12:30:00Z", false, false, null, null);
    slotJob("b", "DEAD", "2026-11-14T13:00:00Z", true, false, "PARTIAL_FAILURE", 2);
    slotJob("c", "SUCCEEDED", "2026-11-14T13:30:00Z", false, false, "BUDGET_EXHAUSTED", 1);
    slotJob("d", "RETRY_WAIT", "2026-11-14T14:00:00Z", true, false, "PARTIAL_FAILURE", 3);
    slotJob("e", "RUNNING", "2026-11-14T14:30:00Z", false, false, null, null);
    slotJob("f", "READY", "2026-11-15T03:00:00Z", false, true, null, null);
    slotJob("g", "CANCELED", "2026-11-15T03:30:00Z", false, false, null, null);
    Instant published =
        jdbc.queryForObject("SELECT published_at FROM draw_result", LocalDateTime.class)
            .toInstant(ZoneOffset.UTC);
    clock.set(published.plusSeconds(3600));
    RoundNotificationStatusQuery query = new RoundNotificationStatusQuery(jdbc, clock);

    RoundNotificationStatusQuery.Status status = query.status(ROUND);
    assertThat(status.slots())
        .extracting(
            RoundNotificationStatusQuery.Slot::outcome,
            RoundNotificationStatusQuery.Slot::causeCode,
            RoundNotificationStatusQuery.Slot::failedCount,
            RoundNotificationStatusQuery.Slot::operatorAlerted)
        .containsExactly(
            tuple("SUCCEEDED", null, 0, false),
            tuple("FAILED", "PARTIAL_FAILURE", 2, true),
            tuple("FAILED", "BUDGET_EXHAUSTED", 1, false),
            tuple("RUNNING", null, 3, false),
            tuple("RUNNING", null, 0, false));
    assertThat(status.autoDispatchClosed()).isFalse();
    assertThat(status.manualDispatchUntil())
        .isEqualTo(published.plus(NotificationPlanCloser.RESEND_WINDOW));

    jdbc.update("UPDATE job SET state = 'SUCCEEDED' WHERE dedupe_key = 'f'");
    assertThat(query.status(ROUND).autoDispatchClosed()).isTrue();
    jdbc.update("UPDATE job SET state = 'READY' WHERE dedupe_key = 'f'");
    clock.set(published.plus(NotificationPlanCloser.RESEND_WINDOW));
    assertThat(query.status(ROUND).autoDispatchClosed()).isTrue();

    jdbc.update("UPDATE lotto_round SET published_result_id = NULL");
    jdbc.update("UPDATE draw_result SET state = 'QUARANTINED'");
    RoundNotificationStatusQuery.Status quarantined = query.status(ROUND);
    assertThat(quarantined.resultState()).isEqualTo("QUARANTINED");
    assertThat(quarantined.manualDispatchUntil()).isNull();
    assertThat(quarantined.autoDispatchClosed()).isFalse();
    jdbc.update("UPDATE draw_result SET state = 'SUPERSEDED'");
    assertThat(query.status(ROUND).resultState()).isEqualTo("NOT_PUBLISHED");
  }

  private void slotJob(
      String key,
      String state,
      String at,
      boolean alert,
      boolean last,
      String cause,
      Integer failed) {
    jdbc.update(
        "INSERT INTO job (public_id, job_type, dedupe_key, state, payload, cursor_json,"
            + " available_at, last_error_code) VALUES (UUID_TO_BIN(UUID()), ?, ?, ?, ?, ?, ?, ?)",
        ResultSlotJobHandler.JOB,
        key,
        state,
        "{\"roundNo\":"
            + ROUND
            + ",\"at\":\""
            + at
            + "\",\"alert\":"
            + alert
            + ",\"last\":"
            + last
            + "}",
        failed == null ? null : "{\"failed\":" + failed + "}",
        LocalDateTime.ofInstant(Instant.parse(at), ZoneOffset.UTC),
        cause);
  }

  @Test
  @DisplayName("수집 job은 미발표면 다음 시각에 다음 시도를 한 번만 등록하고, 게시되면 더 등록하지 않는다")
  void collectJobs() {
    jdbc.update("DELETE FROM job");
    DataSourceTransactionManager tm = new DataSourceTransactionManager(dataSource);
    JobStore jobs = new JobStore(jdbc, tm, clock, new JobProperties(null, null));
    Instant drawAt = Instant.parse("2026-11-14T11:35:00Z");
    clock.set(drawAt.plusSeconds(600));
    jdbc.update("UPDATE lotto_round SET published_result_id = NULL");
    ResultCollectJobHandler pending =
        new ResultCollectJobHandler(
            new ResultCollector(r -> new ParsedDraw.NotAnnounced(), null), jobs, jdbc, tm, clock);

    assertThat(pending.plan(ROUND, drawAt)).isTrue();
    assertThat(new JobRunner(jobs, List.of(pending), tm, clock).runOnce())
        .contains(JobState.SUCCEEDED);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM job WHERE job_type = 'RESULT_COLLECT' AND state = 'READY'"
                    + " AND available_at = '2026-11-14 11:50:00'",
                Long.class))
        .isEqualTo(1);
  }

  @Test
  @DisplayName("이미 게시된 회차의 수집 job은 수집하지 않고 다음 시도도 등록하지 않는다")
  void collectJobSkipsPublishedRound() {
    jdbc.update("DELETE FROM job");
    DataSourceTransactionManager tm = new DataSourceTransactionManager(dataSource);
    JobStore jobs = new JobStore(jdbc, tm, clock, new JobProperties(null, null));
    Instant drawAt = Instant.parse("2026-11-14T11:35:00Z");
    clock.set(drawAt.plusSeconds(600));
    List<Integer> fetched = new ArrayList<>();
    ResultCollectJobHandler handler =
        new ResultCollectJobHandler(
            new ResultCollector(
                r -> {
                  fetched.add(r);
                  return new ParsedDraw.NotAnnounced();
                },
                null),
            jobs,
            jdbc,
            tm,
            clock);

    assertThat(handler.plan(ROUND, drawAt)).isTrue();
    assertThat(new JobRunner(jobs, List.of(handler), tm, clock).runOnce())
        .contains(JobState.SUCCEEDED);
    assertThat(fetched).isEmpty();
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM job WHERE job_type = 'RESULT_COLLECT' AND state = 'READY'",
                Long.class))
        .isZero();
  }

  @Test
  @DisplayName("직접 등록한 게시본은 결과 API가 확인하거나 격리할 때까지 계속 수집한다(05 6절 직접 등록 5번)")
  void collectJobContinuesAfterManualEntry() {
    jdbc.update("DELETE FROM job");
    DataSourceTransactionManager tm = new DataSourceTransactionManager(dataSource);
    JobStore jobs = new JobStore(jdbc, tm, clock, new JobProperties(null, null));
    Instant drawAt = Instant.parse("2026-11-14T11:35:00Z");
    clock.set(drawAt.plusSeconds(600));
    jdbc.update(
        "UPDATE draw_result d JOIN lotto_round r ON r.published_result_id = d.id"
            + " SET d.source = 'MANUAL', d.api_confirmed_at = NULL,"
            + " d.registered_by_member_id = (SELECT MIN(id) FROM member)");
    List<Integer> fetched = new ArrayList<>();
    ResultCollectJobHandler handler =
        new ResultCollectJobHandler(
            new ResultCollector(
                r -> {
                  fetched.add(r);
                  return new ParsedDraw.NotAnnounced();
                },
                null),
            jobs,
            jdbc,
            tm,
            clock);
    JobRunner runner = new JobRunner(jobs, List.of(handler), tm, clock);

    assertThat(handler.plan(ROUND, drawAt)).isTrue();
    assertThat(runner.runOnce()).contains(JobState.SUCCEEDED);
    assertThat(fetched).containsExactly(ROUND);
    assertThat(readyCollectJobs()).isEqualTo(1);

    jdbc.update(
        "UPDATE draw_result d JOIN lotto_round r ON r.published_result_id = d.id"
            + " SET d.api_confirmed_at = NOW(6)");
    clock.set(drawAt.plusSeconds(900));
    assertThat(runner.runOnce()).contains(JobState.SUCCEEDED);
    assertThat(fetched).containsExactly(ROUND);
    assertThat(readyCollectJobs()).isZero();
    jdbc.update(
        "UPDATE draw_result SET source = 'API', registered_by_member_id = NULL,"
            + " api_confirmed_at = NULL");
  }

  private long readyCollectJobs() {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM job WHERE job_type = 'RESULT_COLLECT' AND state = 'READY'",
        Long.class);
  }

  @Test
  @DisplayName(
      "슬롯 사이에 정정되면 접수된 메시지는 그대로, 아직 호출하지 않은 메시지는 새 게시본으로 보내고, 새로 대상이 된 회원은 다음 슬롯에서 1건(T38)")
  void correctionBetweenSlots() {
    long newSubject =
        new AccountStore(jdbc, new DataSourceTransactionManager(dataSource), clock)
            .login(OAuthProvider.KAKAO, "new")
            .subjectId();
    long newMember = memberOf(newSubject);
    consent(newMember);
    record(newSubject, LottoNumbers.of(1, 2, 3, 8, 10, 30).key(), false, true);
    long first = jdbc.queryForObject("SELECT published_result_id FROM lotto_round", Long.class);
    tx.execute(s -> new EvaluationStore(jdbc).evaluateBatch(first, 0, 100));
    clock.set(Instant.parse("2026-11-14T13:00:00Z"));
    runner(
            id ->
                id == message(thirdMember)
                    ? new SendOutcome.NotAccepted("E")
                    : new SendOutcome.Accepted("M-" + id))
        .run(ROUND, new ResultSlot(clock.instant(), false, false));
    assertThat(state(fourthMember)).isEqualTo("ACCEPTED");
    assertThat(state(thirdMember)).isEqualTo("RETRY_WAIT");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM delivery_message", Long.class))
        .isEqualTo(2);

    clock.set(Instant.parse("2026-11-14T13:10:00Z"));
    DataSourceTransactionManager tm = new DataSourceTransactionManager(dataSource);
    ResultPublisher publisher =
        new ResultPublisher(
            jdbc,
            tm,
            new JobStore(jdbc, tm, clock, new JobProperties(null, null)),
            new PrizeProperties(null, null),
            clock);
    publisher.receive(
        new OfficialDraw(
            ROUND,
            new WinningNumbers(LottoNumbers.of(1, 2, 3, 4, 8, 10), LottoNumber.of(11)),
            null,
            null,
            new byte[32]));
    long corrected =
        jdbc.queryForObject("SELECT id FROM draw_result WHERE state = 'QUARANTINED'", Long.class);
    publisher.approve(corrected, 1);
    tx.execute(s -> new EvaluationStore(jdbc).evaluateBatch(corrected, 0, 100));

    clock.set(Instant.parse("2026-11-14T13:30:00Z"));
    List<Long> called = new ArrayList<>();
    runner(
            id -> {
              called.add(id);
              return new SendOutcome.Accepted("M2-" + id);
            })
        .run(ROUND, new ResultSlot(clock.instant(), false, false));

    assertThat(called).containsExactlyInAnyOrder(message(thirdMember), message(newMember));
    assertThat(state(newMember)).isEqualTo("ACCEPTED");
    assertThat(state(thirdMember)).isEqualTo("ACCEPTED");
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM delivery_message WHERE member_id = ?", Long.class, newMember))
        .isEqualTo(1);
    for (long member : List.of(thirdMember, newMember)) {
      assertThat(
              CommunicatedSummary.parse(
                      jdbc.queryForObject(
                          "SELECT CAST(communicated_summary AS CHAR) FROM delivery_message"
                              + " WHERE member_id = ?",
                          String.class,
                          member))
                  .resultId())
          .isEqualTo(corrected);
    }
    assertThat(
            CommunicatedSummary.parse(
                    jdbc.queryForObject(
                        "SELECT CAST(communicated_summary AS CHAR) FROM delivery_message"
                            + " WHERE member_id = ?",
                        String.class,
                        fourthMember))
                .resultId())
        .isEqualTo(first);
  }

  @Test
  @DisplayName("알림 본문 정리 뒤 정정돼도 안내 요약으로 영향 회원 수를 세고, 다시 보내지 않으며 사본은 되살아나지 않는다(T28)")
  void correctionAfterCopyCleanup() {
    tx.execute(s -> store.createMissing(ROUND));
    for (long member : List.of(fourthMember, thirdMember)) {
      long id = message(member);
      inTx(() -> store.startSending(id, 143));
      tx.executeWithoutResult(s -> store.recordOutcome(id, new SendOutcome.Accepted("M-" + id)));
    }
    jdbc.update(
        "UPDATE delivery_message SET payload_ciphertext = ?, recipient_ciphertext = ?",
        new byte[] {9},
        new byte[] {8});
    clock.set(clock.instant().plus(Duration.ofDays(31)));
    assertThat(
            tx.<Integer>execute(s -> new HousekeepingCleaner(jdbc, clock).clearMessageCopies(100)))
        .isEqualTo(2);

    DataSourceTransactionManager tm = new DataSourceTransactionManager(dataSource);
    ResultPublisher publisher =
        new ResultPublisher(
            jdbc,
            tm,
            new JobStore(jdbc, tm, clock, new JobProperties(null, null)),
            new PrizeProperties(null, null),
            clock);
    publisher.receive(
        new OfficialDraw(
            ROUND,
            new WinningNumbers(LottoNumbers.of(10, 20, 30, 40, 41, 42), LottoNumber.of(43)),
            null,
            null,
            new byte[32]));
    long corrected =
        jdbc.queryForObject("SELECT id FROM draw_result WHERE state = 'QUARANTINED'", Long.class);
    publisher.approve(corrected, 1);
    tx.execute(s -> new EvaluationStore(jdbc).evaluateBatch(corrected, 0, 100));

    CorrectionImpact.Impact impact =
        new CorrectionImpact(
                jdbc,
                new NotificationTargetQuery(jdbc),
                new JobStore(jdbc, tm, clock, new JobProperties(null, null)),
                clock)
            .measure(ROUND);
    assertThat(impact).isEqualTo(new CorrectionImpact.Impact(2, 0));
    assertThat(store.sendable(ROUND)).isEmpty();
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM delivery_message WHERE state = 'ACCEPTED'"
                    + " AND payload_ciphertext IS NULL AND recipient_ciphertext IS NULL",
                Long.class))
        .isEqualTo(2);
  }

  private ResultSlotRunner runner(MessageSender sender) {
    DataSourceTransactionManager tm = new DataSourceTransactionManager(dataSource);
    return new ResultSlotRunner(
        jdbc,
        store,
        sender,
        new JobStore(jdbc, tm, clock, new JobProperties(null, null)),
        tm,
        clock);
  }

  private ResultSlot slot(boolean alert, boolean last) {
    return new ResultSlot(clock.instant(), alert, last);
  }

  private long alerts() {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM job WHERE job_type = 'OPERATOR_ALERT'", Long.class);
  }

  private String apply(UnknownMessageReconciler reconciler, long id, ProviderStatus status) {
    return tx.execute(s -> reconciler.apply(id, status));
  }

  private UnknownMessageSweep sweep(MessageLookup lookup) {
    return new UnknownMessageSweep(
        jdbc, lookup, reconciler(), new DataSourceTransactionManager(dataSource));
  }

  private UnknownMessageReconciler reconciler() {
    DataSourceTransactionManager tm = new DataSourceTransactionManager(dataSource);
    return new UnknownMessageReconciler(
        jdbc, budget, new JobStore(jdbc, tm, clock, new JobProperties(null, null)), clock);
  }

  private long unknownMessage(long memberId) {
    tx.execute(s -> store.createMissing(ROUND));
    long id = message(memberId);
    inTx(() -> store.startSending(id, 143));
    tx.executeWithoutResult(s -> store.recordOutcome(id, new SendOutcome.Unknown()));
    jdbc.update("DELETE FROM job WHERE job_type = 'OPERATOR_ALERT'");
    return id;
  }

  private int count(Supplier<Integer> call) {
    Integer n = tx.execute(s -> call.get());
    return n == null ? 0 : n;
  }

  private boolean inTx(Supplier<Boolean> call) {
    return Boolean.TRUE.equals(tx.execute(s -> call.get()));
  }

  private long message(long memberId) {
    return jdbc.queryForObject(
        "SELECT id FROM delivery_message WHERE member_id = ?", Long.class, memberId);
  }

  private Map<String, Object> recipient(long messageId) {
    return jdbc.queryForMap(
        "SELECT recipient_version, consent_event_id, recipient_ciphertext FROM delivery_message"
            + " WHERE id = ?",
        messageId);
  }

  private String state(long memberId) {
    return jdbc.queryForObject(
        "SELECT state FROM delivery_message WHERE member_id = ?", String.class, memberId);
  }

  private Map<String, Object> budgetRow() {
    return jdbc.queryForMap("SELECT reserved_units, spent_units FROM message_budget");
  }

  private long memberOf(long subject) {
    return jdbc.queryForObject(
        "SELECT member_id FROM actor_subject WHERE id = ?", Long.class, subject);
  }

  private void consent(long memberId) {
    jdbc.update(
        "INSERT INTO consent_event (member_id, granted, policy_version, occurred_at) VALUES (?, TRUE, 'v1', NOW(6))",
        memberId);
    long consent = jdbc.queryForObject("SELECT MAX(id) FROM consent_event", Long.class);
    jdbc.update(
        "INSERT INTO member_contact (member_id, address_ciphertext, key_version, verified_at, notify_enabled,"
            + " consent_event_id) VALUES (?, ?, 1, NOW(6), TRUE, ?)",
        memberId,
        new byte[] {1},
        consent);
  }

  private void record(long subject, String key, boolean purchased, boolean recommended) {
    jdbc.update(
        "INSERT INTO lotto_record (public_id, owner_subject_id, round_no, numbers_key, created_at, purchased,"
            + " purchased_at, recommended_at) VALUES (UUID_TO_BIN(UUID()), ?, ?, ?, '2026-11-10 00:00:00', ?, ?, ?)",
        subject,
        ROUND,
        key,
        purchased,
        purchased ? "2026-11-10 00:00:00" : null,
        recommended ? "2026-11-10 00:00:00" : null);
  }
}
