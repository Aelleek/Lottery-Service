package com.lottery.lottery_service.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lottery.lottery_service.account.AccountStore;
import com.lottery.lottery_service.account.MemberLogin;
import com.lottery.lottery_service.auth.OAuthProvider;
import com.lottery.lottery_service.common.command.CommandOperationStore;
import com.lottery.lottery_service.common.command.CommandTokenCodec;
import com.lottery.lottery_service.common.error.BusinessException;
import com.lottery.lottery_service.common.error.ErrorCode;
import com.lottery.lottery_service.common.job.JobProperties;
import com.lottery.lottery_service.common.job.JobStore;
import com.lottery.lottery_service.common.number.LottoNumbers;
import com.lottery.lottery_service.round.RoundStore;
import com.lottery.lottery_service.round.domain.RoundSchedule;
import com.lottery.lottery_service.stats.UsageEventStore;
import com.lottery.lottery_service.support.MutableClock;
import com.lottery.lottery_service.support.MySqlContainerSupport;
import com.lottery.lottery_service.support.TestSecrets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/** 구매 등록 명령을 다시 보냈을 때 처음 결과를 응답 요약으로 돌려주는지 MySQL에서 확인한다(D-057). */
class PurchaseCommandServiceMySqlTest extends MySqlContainerSupport {

  private static final int ROUND = 1200;
  private static final LottoNumbers A = LottoNumbers.of(1, 2, 3, 4, 5, 6);
  private static final LottoNumbers B = LottoNumbers.of(7, 8, 9, 10, 11, 12);

  private static DataSource dataSource;

  private final MutableClock clock = new MutableClock(Instant.parse("2026-11-08T01:00:00Z"));
  private JdbcTemplate jdbc;
  private CommandTokenCodec codec;
  private PurchaseCommandService commands;
  private MemberLogin member;

  @BeforeAll
  static void migrate() throws Exception {
    dataSource = migratedDatabase("purchase_command");
  }

  @BeforeEach
  void setUp() {
    jdbc = new JdbcTemplate(dataSource);
    for (String table :
        List.of(
            "job",
            "usage_event",
            "command_operation",
            "lotto_record",
            "oauth_account",
            "actor_subject",
            "member",
            "lotto_round")) {
      jdbc.update("DELETE FROM " + table);
    }
    DataSourceTransactionManager tm = new DataSourceTransactionManager(dataSource);
    RoundStore rounds = new RoundStore(jdbc, tm);
    Instant now = rounds.dbNow();
    rounds.register(
        new RoundSchedule(
            ROUND, now.minus(Duration.ofDays(1)), now.plusSeconds(3600), now.plusSeconds(7200), 1));
    PurchaseStore purchases =
        new PurchaseStore(
            jdbc,
            rounds,
            new UsageEventStore(jdbc),
            new JobStore(jdbc, tm, clock, new JobProperties(null, null)));
    codec = new CommandTokenCodec(TestSecrets.keys(), clock);
    commands =
        new PurchaseCommandService(
            codec, new CommandOperationStore(jdbc, tm, clock), purchases, jdbc, tm);
    member = new AccountStore(jdbc, tm, clock).login(OAuthProvider.KAKAO, "m");
  }

  @Test
  @DisplayName("같은 요청을 다시 보내면 처음 결과(CREATED)를 그대로 주고 기록은 늘지 않는다. 요약에 번호는 없다")
  void replayReturnsFirstResult() {
    String token = token();
    UUID key = UUID.randomUUID();
    List<PurchaseCommandService.Entry> entries =
        List.of(
            new PurchaseCommandService.Entry("scan-1", A),
            new PurchaseCommandService.Entry("scan-2", B),
            new PurchaseCommandService.Entry("scan-3", A));

    PurchaseCommandService.PurchaseData first = register(token, key, entries);
    assertThat(first.items())
        .extracting(PurchaseCommandService.ItemResult::clientItemId)
        .containsExactly("scan-1", "scan-2", "scan-3");
    assertThat(first.items())
        .extracting(PurchaseCommandService.ItemResult::outcome)
        .containsOnly(PurchaseOutcome.CREATED);
    assertThat(first.items().get(0).recordId()).isEqualTo(first.items().get(2).recordId());

    PurchaseCommandService.PurchaseData again = register(token, key, entries);

    assertThat(again).isEqualTo(first);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lotto_record", Long.class)).isEqualTo(2);
    String summary =
        jdbc.queryForObject("SELECT response_summary FROM command_operation", String.class);
    assertThat(summary).contains("scan-1").doesNotContain(A.key()).doesNotContain("numbers");
  }

  @Test
  @DisplayName("새 키로 같은 번호를 보내면 새 명령이라 ALREADY_REGISTERED이고, 같은 키에 다른 본문은 IDEMPOTENCY_CONFLICT")
  void newKeyAndConflict() {
    String token = token();
    UUID key = UUID.randomUUID();
    register(token, key, List.of(new PurchaseCommandService.Entry("a", A)));

    PurchaseCommandService.PurchaseData second =
        register(token, UUID.randomUUID(), List.of(new PurchaseCommandService.Entry("a", A)));
    assertThat(second.items().get(0).outcome()).isEqualTo(PurchaseOutcome.ALREADY_REGISTERED);

    assertThatThrownBy(
            () -> register(token, key, List.of(new PurchaseCommandService.Entry("a", B))))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.getCode()).isEqualTo(ErrorCode.IDEMPOTENCY_CONFLICT));
  }

  @Test
  @DisplayName("clientItemId가 겹치거나 비었거나 64자를 넘으면 멱등 기록을 만들기 전에 VALIDATION_FAILED")
  void invalidEntries() {
    String token = token();
    for (List<PurchaseCommandService.Entry> bad :
        List.of(
            List.of(
                new PurchaseCommandService.Entry("x", A), new PurchaseCommandService.Entry("x", B)),
            List.of(new PurchaseCommandService.Entry("", A)),
            List.of(new PurchaseCommandService.Entry("x".repeat(65), A)),
            List.<PurchaseCommandService.Entry>of())) {
      assertThatThrownBy(() -> register(token, UUID.randomUUID(), bad))
          .isInstanceOfSatisfying(
              BusinessException.class,
              e -> assertThat(e.getCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM command_operation", Long.class)).isZero();
  }

  @Test
  @DisplayName("없는 회차는 ROUND_CHANGED로 확정 실패해 다시 보내도 같고, 0 이하 회차는 멱등 기록 전에 VALIDATION_FAILED")
  void unknownRound() {
    String token = token();
    UUID key = UUID.randomUUID();
    List<PurchaseCommandService.Entry> entries = List.of(new PurchaseCommandService.Entry("a", A));

    for (int i = 0; i < 2; i++) {
      assertThatThrownBy(
              () ->
                  commands.register(
                      member.subjectId(), member.subjectPublicId(), token, key, 9999, entries))
          .isInstanceOfSatisfying(
              BusinessException.class,
              e -> assertThat(e.getCode()).isEqualTo(ErrorCode.ROUND_CHANGED));
    }
    assertThat(jdbc.queryForObject("SELECT state FROM command_operation", String.class))
        .isEqualTo("FAILED");
    assertThatThrownBy(
            () ->
                commands.register(
                    member.subjectId(),
                    member.subjectPublicId(),
                    token,
                    UUID.randomUUID(),
                    0,
                    entries))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.getCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
  }

  private String token() {
    return codec.issue(member.subjectPublicId(), PurchaseCommandService.OPERATION, null, null);
  }

  private PurchaseCommandService.PurchaseData register(
      String token, UUID key, List<PurchaseCommandService.Entry> entries) {
    return commands
        .register(member.subjectId(), member.subjectPublicId(), token, key, ROUND, entries)
        .data();
  }
}
