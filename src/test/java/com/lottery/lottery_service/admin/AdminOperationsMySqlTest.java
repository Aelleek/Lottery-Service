package com.lottery.lottery_service.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.lottery.lottery_service.account.AccountStore;
import com.lottery.lottery_service.auth.OAuthProvider;
import com.lottery.lottery_service.common.error.BusinessException;
import com.lottery.lottery_service.common.error.ErrorCode;
import com.lottery.lottery_service.common.job.JobProperties;
import com.lottery.lottery_service.common.job.JobStore;
import com.lottery.lottery_service.common.number.LottoNumber;
import com.lottery.lottery_service.common.number.LottoNumbers;
import com.lottery.lottery_service.notification.ManualResendService;
import com.lottery.lottery_service.notification.SlotResult;
import com.lottery.lottery_service.result.domain.WinningNumbers;
import com.lottery.lottery_service.result.publish.PrizeProperties;
import com.lottery.lottery_service.result.publish.ResultPublisher;
import com.lottery.lottery_service.result.source.OfficialDraw;
import com.lottery.lottery_service.round.RoundStore;
import com.lottery.lottery_service.round.domain.RoundSchedule;
import com.lottery.lottery_service.support.MutableClock;
import com.lottery.lottery_service.support.MySqlContainerSupport;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/** 관리자 변경 절차(권한, 사유, 재인증, 감사)를 MySQL에서 확인한다. */
class AdminOperationsMySqlTest extends MySqlContainerSupport {

  private static final int ROUND = 1200;
  private static final WinningNumbers WIN =
      new WinningNumbers(LottoNumbers.of(1, 2, 3, 4, 5, 6), LottoNumber.of(7));
  private static final WinningNumbers OTHER =
      new WinningNumbers(LottoNumbers.of(1, 2, 3, 4, 5, 8), LottoNumber.of(7));

  private static DataSource dataSource;

  private final MutableClock clock = new MutableClock(Instant.parse("2026-11-14T12:00:00Z"));
  private final ManualResendService resend = mock(ManualResendService.class);
  private JdbcTemplate jdbc;
  private ResultPublisher publisher;
  private AdminOperations operations;
  private long admin;
  private long operator;
  private long plain;

  @BeforeAll
  static void migrate() throws Exception {
    dataSource = migratedDatabase("admin_operations");
  }

  @BeforeEach
  void setUp() {
    jdbc = new JdbcTemplate(dataSource);
    for (String t : List.of("admin_audit_event", "admin_grant", "job")) {
      jdbc.update("DELETE FROM " + t);
    }
    jdbc.update("UPDATE lotto_round SET published_result_id = NULL");
    jdbc.update("DELETE FROM draw_prize");
    jdbc.update("DELETE FROM draw_result");
    jdbc.update("DELETE FROM lotto_round");
    for (String t : List.of("oauth_account", "actor_subject", "member")) {
      jdbc.update("DELETE FROM " + t);
    }
    DataSourceTransactionManager tm = new DataSourceTransactionManager(dataSource);
    RoundStore rounds = new RoundStore(jdbc, tm);
    Instant now = rounds.dbNow();
    rounds.register(
        new RoundSchedule(
            ROUND,
            now.minus(Duration.ofDays(7)),
            now.minus(Duration.ofHours(1)),
            now.minusSeconds(60),
            1));
    publisher =
        new ResultPublisher(
            jdbc,
            tm,
            new JobStore(jdbc, tm, clock, new JobProperties(null, null)),
            new PrizeProperties(null, null),
            clock);
    AccountStore accounts = new AccountStore(jdbc, tm, clock);
    admin = accounts.login(OAuthProvider.GOOGLE, "admin").memberId();
    operator = accounts.login(OAuthProvider.GOOGLE, "operator").memberId();
    plain = accounts.login(OAuthProvider.GOOGLE, "plain").memberId();
    grant(admin, "ADMIN");
    grant(operator, "OPERATOR");
    operations =
        new AdminOperations(
            new AdminAccess(jdbc),
            new AdminAuditLog(jdbc, tm, clock),
            publisher,
            resend,
            tm,
            clock);
  }

  @Test
  @DisplayName(
      "격리 결과 승인: OPERATOR는 거부되어 거부 감사, 사유가 없으면 VALIDATION_FAILED, ADMIN은 승인과 성공 감사가 함께 남는다")
  void approve() {
    publisher.receive(new OfficialDraw(ROUND, WIN, null, null, new byte[32]));
    publisher.receive(new OfficialDraw(ROUND, OTHER, null, null, new byte[32]));
    long quarantined =
        jdbc.queryForObject("SELECT id FROM draw_result WHERE state = 'QUARANTINED'", Long.class);

    assertCode(() -> operations.approveResult(operator, quarantined, 1, "정정"), ErrorCode.FORBIDDEN);
    assertCode(
        () -> operations.approveResult(admin, quarantined, 1, " "), ErrorCode.VALIDATION_FAILED);
    operations.approveResult(admin, quarantined, 1, "동행복권 공지 확인");
    assertCode(() -> operations.approveResult(admin, quarantined, 1, "다시"), ErrorCode.NOT_FOUND);

    assertThat(
            jdbc.queryForObject(
                "SELECT published_result_id FROM lotto_round WHERE round_no = ?",
                Long.class,
                ROUND))
        .isEqualTo(quarantined);
    assertThat(audits())
        .extracting(r -> r.get("actor_member_id") + ":" + r.get("outcome"))
        .containsExactly(operator + ":REJECTED", admin + ":SUCCEEDED", admin + ":REJECTED");
    assertThat(audits().get(1))
        .containsEntry("action", "RESULT_PUBLISH")
        .containsEntry("target_key", Long.toString(quarantined))
        .containsEntry("reason", "동행복권 공지 확인");
  }

  @Test
  @DisplayName("결과 직접 등록은 최근 5분 안 재인증이 없으면 REAUTH_REQUIRED와 거부 감사, 있으면 게시와 성공 감사")
  void manualRegistration() {
    assertCode(
        () -> operations.registerManualResult(admin, ROUND, WIN, 0, null, "API 미제공"),
        ErrorCode.REAUTH_REQUIRED);
    assertCode(
        () ->
            operations.registerManualResult(
                admin, ROUND, WIN, 0, clock.instant().minus(Duration.ofMinutes(6)), "API 미제공"),
        ErrorCode.REAUTH_REQUIRED);

    long id =
        operations.registerManualResult(
            admin, ROUND, WIN, 0, clock.instant().minus(Duration.ofMinutes(4)), "API 미제공");

    assertThat(
            jdbc.queryForObject(
                "SELECT published_result_id FROM lotto_round WHERE round_no = ?",
                Long.class,
                ROUND))
        .isEqualTo(id);
    assertThat(audits())
        .extracting(r -> r.get("outcome"))
        .containsExactly("REJECTED", "REJECTED", "SUCCEEDED");
  }

  @Test
  @DisplayName(
      "수동 다시 보내기는 OPERATOR가 할 수 있고 실행 뒤 성공 감사, 관리자 아닌 회원은 FORBIDDEN이고 거부 감사를 남긴다(2026-11-09 Q17)")
  void resend() {
    when(resend.resend(ROUND, AdminRole.OPERATOR)).thenReturn(new SlotResult(true, null, 0, 0, 0));

    assertThat(operations.resendNotifications(operator, ROUND, "제공자 장애 복구").success()).isTrue();
    assertCode(() -> operations.resendNotifications(plain, ROUND, "x"), ErrorCode.FORBIDDEN);

    assertThat(audits())
        .extracting(r -> r.get("actor_member_id") + ":" + r.get("action") + ":" + r.get("outcome"))
        .containsExactly(
            operator + ":NOTIFICATION_RESEND:SUCCEEDED", plain + ":NOTIFICATION_RESEND:REJECTED");
  }

  private List<Map<String, Object>> audits() {
    return jdbc.queryForList(
        "SELECT actor_member_id, action, target_key, reason, outcome FROM admin_audit_event"
            + " ORDER BY id");
  }

  private void grant(long member, String role) {
    jdbc.update(
        "INSERT INTO admin_grant (member_id, role, granted_at) VALUES (?, ?, NOW(6))",
        member,
        role);
  }

  private static void assertCode(ThrowingCallable call, ErrorCode code) {
    assertThatThrownBy(call)
        .isInstanceOfSatisfying(
            BusinessException.class, e -> assertThat(e.getCode()).isEqualTo(code));
  }
}
