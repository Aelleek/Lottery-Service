package com.lottery.lottery_service.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lottery.lottery_service.account.GuestCredentialStore;
import com.lottery.lottery_service.common.command.CommandOperationStore;
import com.lottery.lottery_service.common.error.BusinessException;
import com.lottery.lottery_service.common.error.ErrorCode;
import com.lottery.lottery_service.support.MutableClock;
import com.lottery.lottery_service.support.MySqlContainerSupport;
import com.lottery.lottery_service.support.TestSecrets;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/** 관리자 변경의 요청 키 처리(성공 표시와 업무 변경의 같은 커밋, 키 창)를 MySQL에서 확인한다. */
class AdminCommandRunnerMySqlTest extends MySqlContainerSupport {

  private static final Instant T0 = Instant.parse("2026-11-10T15:00:00Z");

  private static DataSource dataSource;

  private final MutableClock clock = new MutableClock(T0);
  private JdbcTemplate jdbc;
  private GuestCredentialStore guests;
  private AdminCommandRunner runner;
  private long subject;

  @BeforeAll
  static void migrate() throws Exception {
    dataSource = migratedDatabase("admin_command_runner");
  }

  @BeforeEach
  void setUp() {
    jdbc = new JdbcTemplate(dataSource);
    jdbc.update("DELETE FROM command_operation");
    jdbc.update("DELETE FROM guest_credential");
    jdbc.update("DELETE FROM actor_subject");
    DataSourceTransactionManager tm = new DataSourceTransactionManager(dataSource);
    guests = new GuestCredentialStore(jdbc, tm, clock, TestSecrets.keys());
    subject = guests.issue().subjectId();
    runner = new AdminCommandRunner(new CommandOperationStore(jdbc, tm, clock), tm, clock);
  }

  @Test
  @DisplayName("업무 변경 뒤 성공 표시 전에 실패하면 변경도 되돌아가고, 같은 키 재시도는 한 번만 변경하며 그 뒤 재전송은 다시 하지 않는다")
  void changeAndMarkerCommitTogether() {
    UUID key = UUID.randomUUID();
    AtomicInteger calls = new AtomicInteger();

    assertThatThrownBy(
            () ->
                runner.run(
                    subject,
                    "ADMIN_TEST",
                    key,
                    "a",
                    true,
                    id -> {
                      calls.incrementAndGet();
                      guests.issue();
                      throw new IllegalStateException("crash after change");
                    }))
        .isInstanceOf(IllegalStateException.class);
    assertThat(subjects()).isEqualTo(1);

    String summary =
        runner.run(
            subject,
            "ADMIN_TEST",
            key,
            "a",
            true,
            id -> {
              calls.incrementAndGet();
              guests.issue();
              return "{\"n\":1}";
            });
    String again =
        runner.run(
            subject,
            "ADMIN_TEST",
            key,
            "a",
            true,
            id -> {
              calls.incrementAndGet();
              guests.issue();
              return "{\"n\":2}";
            });

    assertThat(summary).isEqualTo("{\"n\":1}");
    assertThat(again.replace(" ", "")).isEqualTo("{\"n\":1}");
    assertThat(calls).hasValue(2);
    assertThat(subjects()).isEqualTo(2);
  }

  @Test
  @DisplayName("처리 권한을 잃으면 성공 표시와 함께 업무 변경도 되돌린다")
  void leaseLossRollsBackChange() {
    UUID key = UUID.randomUUID();

    assertThatThrownBy(
            () ->
                runner.run(
                    subject,
                    "ADMIN_TEST",
                    key,
                    "a",
                    true,
                    id -> {
                      guests.issue();
                      jdbc.update(
                          "UPDATE command_operation SET lease_token = UUID_TO_BIN(UUID())"
                              + " WHERE public_id = UUID_TO_BIN(?)",
                          id.toString());
                      return "{}";
                    }))
        .isInstanceOf(IllegalStateException.class);
    assertThat(subjects()).isEqualTo(1);
  }

  @Test
  @DisplayName("다시 할 수 있는 실패도 처음 요청에서 하루가 지나면 같은 키로 다시 할 수 없다(COMMAND_EXPIRED)")
  void keyWindow() {
    UUID key = UUID.randomUUID();
    assertThatThrownBy(
            () ->
                runner.run(
                    subject,
                    "ADMIN_TEST",
                    key,
                    "a",
                    true,
                    id -> {
                      throw new BusinessException(ErrorCode.REAUTH_REQUIRED, "다시 인증");
                    }))
        .isInstanceOf(BusinessException.class);

    clock.set(T0.plus(AdminCommandRunner.KEY_WINDOW).minusSeconds(1));
    assertThat(runner.run(subject, "ADMIN_TEST", key, "a", true, id -> "{}")).isEqualTo("{}");

    UUID late = UUID.randomUUID();
    clock.set(T0.plus(AdminCommandRunner.KEY_WINDOW));
    assertThatThrownBy(
            () ->
                runner.run(
                    subject,
                    "ADMIN_TEST",
                    late,
                    "a",
                    true,
                    id -> {
                      throw new BusinessException(ErrorCode.REAUTH_REQUIRED, "다시 인증");
                    }))
        .isInstanceOf(BusinessException.class);
    clock.set(T0.plus(AdminCommandRunner.KEY_WINDOW.multipliedBy(2)));
    assertThatThrownBy(() -> runner.run(subject, "ADMIN_TEST", late, "a", true, id -> "{}"))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.getCode()).isEqualTo(ErrorCode.COMMAND_EXPIRED));
  }

  private long subjects() {
    return jdbc.queryForObject("SELECT COUNT(*) FROM actor_subject", Long.class);
  }
}
