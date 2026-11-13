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
import java.util.List;
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
    grants =
        new AdminGrants(
            jdbc,
            tm,
            access,
            new AdminAuditLog(jdbc, tm, clock),
            mfa,
            new JobStore(jdbc, tm, clock, new JobProperties(5, Duration.ofMinutes(5))),
            clock);
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
    List<String> alerts =
        jdbc.queryForList(
            "SELECT payload FROM job WHERE job_type = 'OPERATOR_ALERT'"
                + " AND JSON_UNQUOTE(JSON_EXTRACT(payload, '$.kind')) = 'ADMIN_CHANGE' ORDER BY id",
            String.class);
    assertThat(alerts)
        .filteredOn(p -> p.contains("\"ADMIN_GRANT\"") || p.contains("\"ADMIN_REVOKE\""))
        .hasSize(3);
    assertThat(alerts)
        .allSatisfy(
            p ->
                assertThat(p)
                    .doesNotContain(target.toString())
                    .doesNotContain("운영 담당")
                    .doesNotContain(Long.toString(targetId) + ","));
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
    assertCode(() -> grants.grant(admin, self, AdminRole.OPERATOR, "실수"), ErrorCode.FORBIDDEN);
    assertCode(
        () -> grants.grant(admin, operatorId, AdminRole.ADMIN, " "), ErrorCode.VALIDATION_FAILED);
    assertCode(
        () -> grants.revoke(admin, operatorId, "x".repeat(501)), ErrorCode.VALIDATION_FAILED);
    assertThat(access.roleOf(operator)).contains(AdminRole.OPERATOR);
    UUID plainId = UUID.randomUUID();
    member(plainId);
    assertCode(() -> grants.revoke(admin, plainId, "관리자 아님"), ErrorCode.NOT_FOUND);
    assertCode(() -> grants.resetMfa(admin, plainId, "관리자 아님"), ErrorCode.NOT_FOUND);
    assertCode(() -> grants.resetMfa(admin, self, "실수"), ErrorCode.FORBIDDEN);
    assertCode(
        () -> grants.grant(admin, UUID.randomUUID(), AdminRole.ADMIN, "없는 회원"),
        ErrorCode.NOT_FOUND);
    assertThat(access.roleOf(admin)).contains(AdminRole.ADMIN);
    assertThat(audits("ADMIN_GRANT", "REJECTED")).isEqualTo(3);
    assertThat(audits("ADMIN_REVOKE", "REJECTED")).isEqualTo(2);
    assertThat(audits("ADMIN_MFA_RESET", "REJECTED")).isEqualTo(2);
  }

  @Test
  @DisplayName("관리자 역할이 전혀 없는 회원의 부여 시도도 FORBIDDEN이고 그 회원 이름으로 거부 감사가 남는다(2026-11-09 Q17)")
  void memberWithoutRoleIsAudited() {
    long plain = member(UUID.randomUUID());

    assertCode(
        () -> grants.grant(plain, UUID.randomUUID(), AdminRole.ADMIN, "권한 올리기"),
        ErrorCode.FORBIDDEN);

    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM admin_audit_event WHERE actor_member_id = ?"
                    + " AND action = 'ADMIN_GRANT' AND outcome = 'REJECTED'",
                Long.class,
                plain))
        .isEqualTo(1);
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

  @Test
  @DisplayName("하나뿐인 ADMIN도 관리 명령으로 자기 2차 인증을 초기화하고 권한을 회수할 수 있다. 감사와 운영자 알림이 남는다(C-33)")
  void soleAdminByCommand() {
    UUID soleId = UUID.randomUUID();
    long sole = member(soleId);
    grants.grantByCommand(soleId, AdminRole.ADMIN, "첫 관리자 지정");
    mfa.enroll(sole);
    assertCode(() -> grants.resetMfa(sole, soleId, "기기 분실"), ErrorCode.FORBIDDEN);

    grants.resetMfaByCommand(soleId, "하나뿐인 ADMIN의 기기 분실");

    assertThat(
            jdbc.queryForObject(
                "SELECT mfa_secret IS NULL FROM admin_grant WHERE member_id = ?",
                Boolean.class,
                sole))
        .isTrue();
    assertThat(access.roleOf(sole)).contains(AdminRole.ADMIN);
    assertThat(mfa.enroll(sole).recoveryCodes()).hasSize(10);

    grants.revokeByCommand(soleId, "계정 도용 의심");

    assertThat(access.roleOf(sole)).isEmpty();
    assertThat(audits("ADMIN_MFA_RESET_BY_COMMAND", "SUCCEEDED")).isEqualTo(1);
    assertThat(audits("ADMIN_REVOKE_BY_COMMAND", "SUCCEEDED")).isEqualTo(1);
    assertThat(
            jdbc.queryForList(
                "SELECT JSON_UNQUOTE(JSON_EXTRACT(payload, '$.action')) FROM job"
                    + " WHERE job_type = 'OPERATOR_ALERT'"
                    + " AND JSON_EXTRACT(payload, '$.kind') = 'ADMIN_CHANGE' ORDER BY id",
                String.class))
        .containsExactly(
            "ADMIN_GRANT_BY_COMMAND", "ADMIN_MFA_RESET_BY_COMMAND", "ADMIN_REVOKE_BY_COMMAND");
  }

  @Test
  @DisplayName("관리 명령의 회수·초기화도 권한이 없는 회원이나 빈 사유는 받지 않고 아무것도 바꾸지 않는다")
  void commandRejectsWithoutChange() {
    UUID plainId = UUID.randomUUID();
    member(plainId);
    UUID adminId = UUID.randomUUID();
    long admin = member(adminId);
    grants.grantByCommand(adminId, AdminRole.ADMIN, "첫 관리자 지정");

    assertCode(() -> grants.revokeByCommand(plainId, "권한 없음"), ErrorCode.NOT_FOUND);
    assertCode(() -> grants.resetMfaByCommand(plainId, "권한 없음"), ErrorCode.NOT_FOUND);
    assertCode(() -> grants.revokeByCommand(UUID.randomUUID(), "없는 회원"), ErrorCode.NOT_FOUND);
    assertCode(() -> grants.revokeByCommand(adminId, " "), ErrorCode.VALIDATION_FAILED);
    grants.revokeByCommand(adminId, "회수");
    assertCode(() -> grants.revokeByCommand(adminId, "두 번째"), ErrorCode.NOT_FOUND);

    assertThat(access.roleOf(admin)).isEmpty();
    assertThat(audits("ADMIN_REVOKE_BY_COMMAND", "SUCCEEDED")).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM admin_audit_event WHERE action LIKE '%BY_COMMAND'",
                Long.class))
        .isEqualTo(2);
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
