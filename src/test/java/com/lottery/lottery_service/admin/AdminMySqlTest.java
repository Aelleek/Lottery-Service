package com.lottery.lottery_service.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lottery.lottery_service.common.error.BusinessException;
import com.lottery.lottery_service.common.error.ErrorCode;
import com.lottery.lottery_service.support.MySqlContainerSupport;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 관리자 권한 확인과 감사 기록을 MySQL에서 확인한다. */
class AdminMySqlTest extends MySqlContainerSupport {

  private static final Instant T0 = Instant.parse("2026-11-06T10:00:00Z");

  private static DataSource dataSource;

  private JdbcTemplate jdbc;
  private TransactionTemplate tx;
  private AdminAccess access;
  private AdminAuditLog audit;

  @BeforeAll
  static void migrate() throws Exception {
    dataSource = migratedDatabase("admin_base");
  }

  @BeforeEach
  void setUp() {
    jdbc = new JdbcTemplate(dataSource);
    jdbc.update("DELETE FROM admin_audit_event");
    jdbc.update("DELETE FROM admin_grant");
    jdbc.update("DELETE FROM member");
    DataSourceTransactionManager tm = new DataSourceTransactionManager(dataSource);
    tx = new TransactionTemplate(tm);
    access = new AdminAccess(jdbc);
    audit = new AdminAuditLog(jdbc, tm, Clock.fixed(T0, ZoneOffset.UTC));
  }

  @Test
  @DisplayName("ADMIN은 OPERATOR 일도 하고, OPERATOR는 ADMIN 일을 못 하며, 권한이 없으면 FORBIDDEN")
  void roleHierarchy() {
    long admin = member("ACTIVE");
    long operator = member("ACTIVE");
    long plain = member("ACTIVE");
    grant(admin, "ADMIN");
    grant(operator, "OPERATOR");

    assertThat(access.require(admin, AdminRole.OPERATOR)).isEqualTo(AdminRole.ADMIN);
    assertThat(access.require(operator, AdminRole.OPERATOR)).isEqualTo(AdminRole.OPERATOR);
    assertForbidden(() -> access.require(operator, AdminRole.ADMIN));
    assertForbidden(() -> access.require(plain, AdminRole.OPERATOR));
  }

  @Test
  @DisplayName("회수된 권한과 탈퇴 처리 중인 회원의 권한은 없는 것으로 본다")
  void revokedOrInactiveHasNoRole() {
    long revoked = member("ACTIVE");
    long leaving = member("WITHDRAWING");
    grant(revoked, "ADMIN");
    grant(leaving, "ADMIN");
    jdbc.update("UPDATE admin_grant SET revoked_at = NOW(6) WHERE member_id = ?", revoked);

    assertThat(access.roleOf(revoked)).isEmpty();
    assertThat(access.roleOf(leaving)).isEmpty();
    assertForbidden(() -> access.require(leaving, AdminRole.OPERATOR));
  }

  @Test
  @DisplayName("감사 기록은 사유·전후 값·결과와 1년 뒤 만료 시각을 남긴다")
  void recordStoresEventWithOneYearExpiry() {
    long admin = member("ACTIVE");

    audit.record(event(admin, AdminAuditOutcome.SUCCEEDED));

    Map<String, Object> row = jdbc.queryForMap("SELECT * FROM admin_audit_event");
    assertThat(row)
        .containsEntry("action", "RESULT_PUBLISH")
        .containsEntry("reason", "공식 발표와 대조함")
        .containsEntry("outcome", "SUCCEEDED");
    assertThat((LocalDateTime) row.get("occurred_at"))
        .isEqualTo(LocalDateTime.ofInstant(T0, ZoneOffset.UTC));
    assertThat((LocalDateTime) row.get("expires_at"))
        .isEqualTo(LocalDateTime.parse("2027-11-06T10:00:00"));
  }

  @Test
  @DisplayName("같은 트랜잭션 기록은 변경과 함께 rollback되고, 따로 남긴 거부 기록은 남는다")
  void independentRecordSurvivesRollback() {
    long admin = member("ACTIVE");

    tx.executeWithoutResult(
        status -> {
          audit.record(event(admin, AdminAuditOutcome.SUCCEEDED));
          audit.recordIndependently(event(admin, AdminAuditOutcome.REJECTED));
          status.setRollbackOnly();
        });

    assertThat(jdbc.queryForList("SELECT outcome FROM admin_audit_event", String.class))
        .containsExactly("REJECTED");
  }

  @Test
  @DisplayName("사유가 비었으면 기록을 만들지 않는다")
  void reasonRequired() {
    assertThatThrownBy(
            () ->
                new AdminAuditEvent(
                    1,
                    "RESULT_PUBLISH",
                    "DRAW_RESULT",
                    "1",
                    " ",
                    null,
                    null,
                    AdminAuditOutcome.SUCCEEDED))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new AdminAuditEvent(
                    1,
                    "RESULT_PUBLISH",
                    "DRAW_RESULT",
                    "1",
                    "x".repeat(501),
                    null,
                    null,
                    AdminAuditOutcome.SUCCEEDED))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private AdminAuditEvent event(long actor, AdminAuditOutcome outcome) {
    return new AdminAuditEvent(
        actor,
        "RESULT_PUBLISH",
        "DRAW_RESULT",
        "42",
        "공식 발표와 대조함",
        "{\"publicationVersion\":1}",
        "{\"publicationVersion\":2}",
        outcome);
  }

  private long member(String state) {
    String publicId = UUID.randomUUID().toString();
    jdbc.update(
        "INSERT INTO member (public_id, state, created_at) VALUES (UUID_TO_BIN(?), ?, NOW(6))",
        publicId,
        state);
    return jdbc.queryForObject(
        "SELECT id FROM member WHERE public_id = UUID_TO_BIN(?)", Long.class, publicId);
  }

  private void grant(long memberId, String role) {
    jdbc.update(
        "INSERT INTO admin_grant (member_id, role, granted_at) VALUES (?, ?, NOW(6))",
        memberId,
        role);
  }

  private static void assertForbidden(Runnable call) {
    assertThatThrownBy(call::run)
        .isInstanceOfSatisfying(
            BusinessException.class, e -> assertThat(e.getCode()).isEqualTo(ErrorCode.FORBIDDEN));
  }
}
