package com.lottery.lottery_service.admin.mfa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lottery.lottery_service.common.error.BusinessException;
import com.lottery.lottery_service.common.job.JobProperties;
import com.lottery.lottery_service.common.job.JobStore;
import com.lottery.lottery_service.support.MutableClock;
import com.lottery.lottery_service.support.MySqlContainerSupport;
import com.lottery.lottery_service.support.TestSecrets;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/** 관리자 인증 앱 등록·확인·잠금·복구 코드를 MySQL에서 확인한다(D-047). */
class AdminMfaStoreMySqlTest extends MySqlContainerSupport {

  private static final Instant T0 = Instant.parse("2026-11-08T15:00:15Z");

  private static DataSource dataSource;

  private final MutableClock clock = new MutableClock(T0);
  private JdbcTemplate jdbc;
  private AdminMfaStore store;
  private long admin;

  @BeforeAll
  static void migrate() throws Exception {
    dataSource = migratedDatabase("admin_mfa");
  }

  @BeforeEach
  void setUp() {
    jdbc = new JdbcTemplate(dataSource);
    jdbc.update("DELETE FROM job");
    jdbc.update("DELETE FROM admin_grant");
    jdbc.update("DELETE FROM member");
    DataSourceTransactionManager tm = new DataSourceTransactionManager(dataSource);
    store =
        new AdminMfaStore(
            jdbc,
            tm,
            TestSecrets.keys(),
            new JobStore(jdbc, tm, clock, new JobProperties(5, Duration.ofMinutes(5))),
            clock);
    admin = member();
    jdbc.update(
        "INSERT INTO admin_grant (member_id, role, granted_at) VALUES (?, 'ADMIN', NOW(6))", admin);
  }

  @Test
  @DisplayName("등록 뒤 첫 코드로 확인해야 로그인에 쓸 수 있고, 비밀값은 암호문으로만 저장된다")
  void enrollConfirmVerify() {
    AdminMfaStore.Enrollment enrollment = store.enroll(admin);
    byte[] secret = decode(enrollment.secret());

    assertThat(enrollment.recoveryCodes()).hasSize(10).doesNotHaveDuplicates();
    assertThat(enrollment.otpauthUri()).startsWith("otpauth://totp/").contains("period=30");
    byte[] stored =
        jdbc.queryForObject(
            "SELECT mfa_secret FROM admin_grant WHERE member_id = ?", byte[].class, admin);
    assertThat(new String(stored, java.nio.charset.StandardCharsets.ISO_8859_1))
        .doesNotContain(new String(secret, java.nio.charset.StandardCharsets.ISO_8859_1));
    assertThat(store.verify(admin, code(secret, 0))).isEqualTo(AdminMfaStore.Outcome.NOT_ENROLLED);

    assertThat(store.confirm(admin, code(secret, 0))).isEqualTo(AdminMfaStore.Outcome.OK);
    assertThatThrownBy(() -> store.enroll(admin)).isInstanceOf(BusinessException.class);

    clock.set(T0.plusSeconds(30));
    assertThat(store.verify(admin, code(secret, 0))).isEqualTo(AdminMfaStore.Outcome.OK);
  }

  @Test
  @DisplayName("앞뒤 한 단위까지 받고, 이미 받은 단위 이하의 코드는 다시 받지 않는다")
  void windowAndReplay() {
    byte[] secret = enrolled();
    clock.set(T0.plusSeconds(300));

    assertThat(store.verify(admin, code(secret, -60))).isEqualTo(AdminMfaStore.Outcome.INVALID);
    assertThat(store.verify(admin, code(secret, -30))).isEqualTo(AdminMfaStore.Outcome.OK);
    assertThat(store.verify(admin, code(secret, -30))).isEqualTo(AdminMfaStore.Outcome.INVALID);
    assertThat(store.verify(admin, code(secret, 30))).isEqualTo(AdminMfaStore.Outcome.OK);
    assertThat(store.verify(admin, code(secret, 0))).isEqualTo(AdminMfaStore.Outcome.INVALID);
  }

  @Test
  @DisplayName("5번 틀리면 15분 잠그고 운영자 알림 job을 남긴다. 잠긴 동안은 맞는 코드도 받지 않는다")
  void lockAfterFiveFailures() {
    byte[] secret = enrolled();
    clock.set(T0.plusSeconds(120));
    for (int i = 0; i < 4; i++) {
      assertThat(store.verify(admin, "000000".equals(code(secret, 0)) ? "111111" : "000000"))
          .isEqualTo(AdminMfaStore.Outcome.INVALID);
    }
    assertThat(store.verify(admin, "abc")).isEqualTo(AdminMfaStore.Outcome.LOCKED);
    assertThat(store.verify(admin, code(secret, 0))).isEqualTo(AdminMfaStore.Outcome.LOCKED);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM job WHERE job_type = 'OPERATOR_ALERT'"
                    + " AND JSON_EXTRACT(payload, '$.kind') = 'ADMIN_MFA_LOCKED'",
                Long.class))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT JSON_KEYS(payload) FROM job WHERE job_type = 'OPERATOR_ALERT'",
                String.class))
        .isEqualTo("[\"kind\", \"lockMinutes\"]");

    clock.set(T0.plusSeconds(120).plus(AdminMfaStore.LOCK));
    assertThat(store.verify(admin, code(secret, 0))).isEqualTo(AdminMfaStore.Outcome.OK);
  }

  @Test
  @DisplayName("복구 코드는 한 번만 쓸 수 있고, 초기화하면 다시 등록할 수 있다")
  void recoveryCodesAndReset() {
    AdminMfaStore.Enrollment enrollment = store.enroll(admin);
    store.confirm(admin, code(decode(enrollment.secret()), 0));
    String recovery = enrollment.recoveryCodes().get(3);

    assertThat(store.verifyRecovery(admin, recovery.toLowerCase()))
        .isEqualTo(AdminMfaStore.Outcome.OK);
    assertThat(store.verifyRecovery(admin, recovery)).isEqualTo(AdminMfaStore.Outcome.INVALID);
    assertThat(store.verifyRecovery(admin, enrollment.recoveryCodes().get(4)))
        .isEqualTo(AdminMfaStore.Outcome.OK);

    store.reset(admin);
    assertThat(store.verify(admin, "123456")).isEqualTo(AdminMfaStore.Outcome.NOT_ENROLLED);
    assertThat(store.enroll(admin).recoveryCodes()).hasSize(10);
  }

  @Test
  @DisplayName("권한이 회수됐거나 회원이 ACTIVE가 아니면 등록·확인할 수 없다")
  void revokedOrInactive() {
    byte[] secret = enrolled();
    jdbc.update("UPDATE admin_grant SET revoked_at = NOW(6) WHERE member_id = ?", admin);
    assertThat(store.verify(admin, code(secret, 0))).isEqualTo(AdminMfaStore.Outcome.NOT_ENROLLED);

    long other = member();
    assertThatThrownBy(() -> store.enroll(other)).isInstanceOf(BusinessException.class);
  }

  private byte[] enrolled() {
    byte[] secret = decode(store.enroll(admin).secret());
    assertThat(store.confirm(admin, code(secret, 0))).isEqualTo(AdminMfaStore.Outcome.OK);
    return secret;
  }

  private String code(byte[] secret, long offsetSeconds) {
    return Totp.code(secret, Totp.step(clock.instant().plusSeconds(offsetSeconds)));
  }

  private long member() {
    String publicId = UUID.randomUUID().toString();
    jdbc.update(
        "INSERT INTO member (public_id, state, created_at) VALUES (UUID_TO_BIN(?), 'ACTIVE', NOW(6))",
        publicId);
    return jdbc.queryForObject(
        "SELECT id FROM member WHERE public_id = UUID_TO_BIN(?)", Long.class, publicId);
  }

  private static byte[] decode(String base32) {
    String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    int buffer = 0;
    int bits = 0;
    for (char c : base32.toCharArray()) {
      buffer = (buffer << 5) | alphabet.indexOf(c);
      bits += 5;
      if (bits >= 8) {
        out.write((buffer >> (bits - 8)) & 0xff);
        bits -= 8;
      }
    }
    return out.toByteArray();
  }
}
