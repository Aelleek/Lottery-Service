package com.lottery.lottery_service.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lottery.lottery_service.admin.mfa.AdminMfaStore;
import com.lottery.lottery_service.common.error.BusinessException;
import com.lottery.lottery_service.common.error.ErrorCode;
import com.lottery.lottery_service.common.job.JobProperties;
import com.lottery.lottery_service.common.job.JobStore;
import com.lottery.lottery_service.support.MySqlContainerSupport;
import com.lottery.lottery_service.support.TestSecrets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/** 관리자 권한 부여·회수·인증 초기화와 감사 기록을 MySQL에서 확인한다(C-33). */
class AdminGrantsMySqlTest extends MySqlContainerSupport {

  private static final Instant T0 = Instant.parse("2026-11-08T15:00:00Z");

  private static DataSource dataSource;

  private JdbcTemplate jdbc;
  private AdminGrants grants;
  private AdminAccess access;
  private AdminMfaStore mfa;

  @BeforeAll
  static void migrate() throws Exception {
    dataSource = migratedDatabase("admin_grants");
  }

  @BeforeEach
  void setUp() {
    jdbc = new JdbcTemplate(dataSource);
    jdbc.update("DELETE FROM job");
    jdbc.update("DELETE FROM admin_audit_event");
    jdbc.update("DELETE FROM admin_grant");
    jdbc.update("DELETE FROM member");
    DataSourceTransactionManager tm = new DataSourceTransactionManager(dataSource);
    Clock clock = Clock.fixed(T0, ZoneOffset.UTC);
    access = new AdminAccess(jdbc);
    mfa =
        new AdminMfaStore(
            jdbc,
            tm,
            TestSecrets.keys(),
            new JobStore(jdbc, tm, clock, new JobProperties(5, Duration.ofMinutes(5))),
            clock);
    grants = new AdminGrants(jdbc, tm, access, new AdminAuditLog(jdbc, tm, clock), mfa, clock);
  }

  @Test
  @DisplayName("관리 명령으로 첫 ADMIN을 주고 감사 기록을 남긴다")
  void bootstrapByCommand() {
    UUID first = UUID.randomUUID();
    long id = member(first);

    grants.grantByCommand(first, AdminRole.ADMIN, "첫 관리자 지정");

    assertThat(access.roleOf(id)).contains(AdminRole.ADMIN);
    assertThat(audits("ADMIN_GRANT_BY_COMMAND", "SUCCEEDED")).isEqualTo(1);
  }

  @Test
  @DisplayName("ADMIN은 다른 회원에게 권한을 주고 회수하며, 회수 뒤 다시 주면 되살아난다")
  void grantAndRevoke() {
    long admin = admin();
    UUID target = UUID.randomUUID();
    long targetId = member(target);

    grants.grant(admin, target, AdminRole.OPERATOR, "운영 담당 추가");
    assertThat(access.roleOf(targetId)).contains(AdminRole.OPERATOR);
    grants.revoke(admin, target, "담당 종료");
    assertThat(access.roleOf(targetId)).isEmpty();
    grants.grant(admin, target, AdminRole.ADMIN, "다시 지정");
    assertThat(access.roleOf(targetId)).contains(AdminRole.ADMIN);
    assertThat(
            jdbc.queryForObject(
                "SELECT granted_by_member_id FROM admin_grant WHERE member_id = ?",
                Long.class,
                targetId))
        .isEqualTo(admin);
    assertThat(audits("ADMIN_GRANT", "SUCCEEDED")).isEqualTo(2);
    assertThat(audits("ADMIN_REVOKE", "SUCCEEDED")).isEqualTo(1);
  }

  @Test
  @DisplayName("OPERATOR는 권한을 줄 수 없고, ADMIN도 자기 권한 회수·인증 초기화는 못 한다. 거부도 감사에 남는다")
  void rejectedChanges() {
    long admin = admin();
    UUID self =
        jdbc.queryForObject(
            "SELECT BIN_TO_UUID(public_id) FROM member WHERE id = ?", UUID.class, admin);
    UUID operatorId = UUID.randomUUID();
    long operator = member(operatorId);
    grants.grant(admin, operatorId, AdminRole.OPERATOR, "운영 담당");

    assertCode(
        () -> grants.grant(operator, UUID.randomUUID(), AdminRole.ADMIN, "권한 올리기"),
        ErrorCode.FORBIDDEN);
    assertCode(() -> grants.revoke(admin, self, "실수"), ErrorCode.FORBIDDEN);
    assertCode(() -> grants.resetMfa(admin, self, "실수"), ErrorCode.FORBIDDEN);
    assertCode(
        () -> grants.grant(admin, UUID.randomUUID(), AdminRole.ADMIN, "없는 회원"),
        ErrorCode.NOT_FOUND);
    assertThat(access.roleOf(admin)).contains(AdminRole.ADMIN);
    assertThat(audits("ADMIN_GRANT", "REJECTED")).isEqualTo(2);
    assertThat(audits("ADMIN_REVOKE", "REJECTED")).isEqualTo(1);
    assertThat(audits("ADMIN_MFA_RESET", "REJECTED")).isEqualTo(1);
  }

  @Test
  @DisplayName("ADMIN이 다른 관리자의 인증을 초기화하면 다시 등록할 수 있다")
  void resetOtherMfa() {
    long admin = admin();
    UUID otherId = UUID.randomUUID();
    long other = member(otherId);
    grants.grant(admin, otherId, AdminRole.OPERATOR, "운영 담당");
    mfa.enroll(other);

    grants.resetMfa(admin, otherId, "기기 분실");

    assertThat(
            jdbc.queryForObject(
                "SELECT mfa_secret IS NULL FROM admin_grant WHERE member_id = ?",
                Boolean.class,
                other))
        .isTrue();
    assertThat(mfa.enroll(other).recoveryCodes()).hasSize(10);
    assertThat(audits("ADMIN_MFA_RESET", "SUCCEEDED")).isEqualTo(1);
  }

  private long admin() {
    UUID id = UUID.randomUUID();
    long memberId = member(id);
    grants.grantByCommand(id, AdminRole.ADMIN, "테스트 관리자");
    return memberId;
  }

  private long member(UUID publicId) {
    jdbc.update(
        "INSERT INTO member (public_id, state, created_at) VALUES (UUID_TO_BIN(?), 'ACTIVE', NOW(6))",
        publicId.toString());
    return jdbc.queryForObject(
        "SELECT id FROM member WHERE public_id = UUID_TO_BIN(?)", Long.class, publicId.toString());
  }

  private long audits(String action, String outcome) {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM admin_audit_event WHERE action = ? AND outcome = ?",
        Long.class,
        action,
        outcome);
  }

  private static void assertCode(Runnable call, ErrorCode code) {
    assertThatThrownBy(call::run)
        .isInstanceOfSatisfying(
            BusinessException.class, e -> assertThat(e.getCode()).isEqualTo(code));
  }
}
