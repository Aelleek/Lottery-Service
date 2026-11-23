package com.lottery.lottery_service.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lottery.lottery_service.common.web.SecuritySignals;
import com.lottery.lottery_service.support.MySqlContainerSupport;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/** 관리자 역할이 없는 회원의 관리자 API 거부 기록(D-062)을 MySQL에서 확인한다. */
class AdminAccessDenialsMySqlTest extends MySqlContainerSupport {

  /** 서울 2026-11-23 23:59:59. */
  private static final Instant LATE = Instant.parse("2026-11-23T14:59:59Z");

  /** 서울 2026-11-24 00:00:00. */
  private static final Instant NEXT_DAY = Instant.parse("2026-11-23T15:00:00Z");

  private static DataSource dataSource;

  private JdbcTemplate jdbc;
  private DataSourceTransactionManager tm;

  @BeforeAll
  static void migrate() throws Exception {
    dataSource = migratedDatabase("admin_denials");
  }

  @BeforeEach
  void setUp() {
    jdbc = new JdbcTemplate(dataSource);
    jdbc.update("DELETE FROM admin_audit_event");
    jdbc.update("DELETE FROM admin_grant");
    jdbc.update("DELETE FROM member");
    tm = new DataSourceTransactionManager(dataSource);
  }

  @Test
  @DisplayName("거부는 매번 신호로 세고, 감사에는 사유 없는 행을 회원마다 서울 날짜로 하루 1건만 남긴다")
  void oncePerSeoulDay() {
    long member = member();
    long other = member();
    SecuritySignals signals = new SecuritySignals();
    AdminAccessDenials late = denials(signals, LATE);

    late.deny(member, "/api/v1/admin/rounds/1200/notification-status");
    late.deny(member, "/api/v1/admin/stats");
    late.deny(other, "/api/v1/admin/stats");

    assertThat(rows(member))
        .containsExactly(
            Map.of(
                "action", "ADMIN_ACCESS_DENIED",
                "target_type", "ADMIN_API",
                "target_key", "/api/v1/admin/rounds/{id}/notification-status",
                "outcome", "REJECTED"));
    assertThat(jdbc.queryForList("SELECT reason FROM admin_audit_event", String.class))
        .containsExactly(null, null);
    assertThat(rows(other)).hasSize(1);
    assertThat(signals.drain())
        .containsEntry(
            SecuritySignals.Kind.ADMIN_API_DENIED,
            new SecuritySignals.Summary(3, "/api/v1/admin/stats"));

    denials(signals, LATE).deny(member, "/api/v1/admin/stats");
    assertThat(rows(member)).hasSize(1);

    AdminAccessDenials tomorrow = denials(signals, NEXT_DAY);
    tomorrow.deny(member, "/api/v1/admin/stats");
    tomorrow.deny(member, "/api/v1/admin/stats");
    assertThat(rows(member)).hasSize(2);
    assertThat(signals.drain().get(SecuritySignals.Kind.ADMIN_API_DENIED).count()).isEqualTo(3);
  }

  @Test
  @DisplayName("같은 회원의 거부가 동시에 몰려도 감사 행은 1건이다")
  void concurrentDenialsWriteOneRow() throws Exception {
    long member = member();
    AdminAccessDenials denials = denials(new SecuritySignals(), LATE);
    ExecutorService pool = Executors.newFixedThreadPool(8);
    try {
      Callable<Void> call =
          () -> {
            denials.deny(member, "/api/v1/admin/stats");
            return null;
          };
      List<Future<Void>> futures = pool.invokeAll(Collections.nCopies(32, call));
      for (Future<Void> f : futures) {
        f.get();
      }
    } finally {
      pool.shutdownNow();
    }
    assertThat(rows(member)).hasSize(1);
  }

  @Test
  @DisplayName("감사를 남기지 못해도 예외를 내지 않고, 다음 거부에서 다시 남긴다")
  void auditFailureIsSwallowedAndRetried() {
    long member = member();
    SecuritySignals signals = new SecuritySignals();
    Clock clock = Clock.fixed(LATE, ZoneOffset.UTC);
    AtomicBoolean fail = new AtomicBoolean(true);
    AdminAuditLog flaky =
        new AdminAuditLog(jdbc, tm, clock) {
          @Override
          public void recordIndependently(AdminAuditEvent event) {
            if (fail.getAndSet(false)) {
              throw new IllegalStateException("blocked");
            }
            super.recordIndependently(event);
          }
        };
    AdminAccessDenials denials = new AdminAccessDenials(signals, flaky, jdbc, clock);

    denials.deny(member, "/api/v1/admin/stats");
    assertThat(rows(member)).isEmpty();

    denials.deny(member, "/api/v1/admin/stats");
    assertThat(rows(member)).hasSize(1);
    assertThat(signals.drain().get(SecuritySignals.Kind.ADMIN_API_DENIED).count()).isEqualTo(2);
  }

  @Test
  @DisplayName("긴 경로는 대상 키 길이를 넘지 않게 기타로 남긴다")
  void longRouteFallsBackToOther() {
    long member = member();
    String path = "/api/v1/admin/" + "a".repeat(67);

    denials(new SecuritySignals(), LATE).deny(member, path);

    assertThat(rows(member).get(0)).containsEntry("target_key", SecuritySignals.OTHER_PATH);
  }

  @Test
  @DisplayName("DB도 사유 없는 행은 거부 종류만 받는다")
  void schemaAllowsNullReasonOnlyForDenials() {
    long member = member();
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "INSERT INTO admin_audit_event (actor_member_id, action, target_type,"
                        + " target_key, reason, outcome, occurred_at, expires_at)"
                        + " VALUES (?, 'RESULT_PUBLISH', 'DRAW_RESULT', '1', NULL, 'REJECTED',"
                        + " NOW(6), NOW(6))",
                    member))
        .hasMessageContaining("ck_admin_audit_event_1");
  }

  private AdminAccessDenials denials(SecuritySignals signals, Instant now) {
    Clock clock = Clock.fixed(now, ZoneOffset.UTC);
    return new AdminAccessDenials(signals, new AdminAuditLog(jdbc, tm, clock), jdbc, clock);
  }

  private List<Map<String, Object>> rows(long member) {
    return jdbc.queryForList(
        "SELECT action, target_type, target_key, outcome FROM admin_audit_event"
            + " WHERE actor_member_id = ? ORDER BY id",
        member);
  }

  private long member() {
    String publicId = UUID.randomUUID().toString();
    jdbc.update(
        "INSERT INTO member (public_id, state, created_at) VALUES (UUID_TO_BIN(?), 'ACTIVE',"
            + " NOW(6))",
        publicId);
    return jdbc.queryForObject(
        "SELECT id FROM member WHERE public_id = UUID_TO_BIN(?)", Long.class, publicId);
  }
}
