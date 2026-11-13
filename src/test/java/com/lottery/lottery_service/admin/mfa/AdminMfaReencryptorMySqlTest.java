package com.lottery.lottery_service.admin.mfa;

import static org.assertj.core.api.Assertions.assertThat;

import com.lottery.lottery_service.common.job.JobProperties;
import com.lottery.lottery_service.common.job.JobStore;
import com.lottery.lottery_service.common.secret.KeyReencryptor;
import com.lottery.lottery_service.common.secret.SecretKeys;
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

/** 관리자 인증 앱 키를 바꾼 뒤 남은 비밀값을 새 버전으로 다시 암호화하는지 MySQL에서 확인한다(03 6.6, C-30). */
class AdminMfaReencryptorMySqlTest extends MySqlContainerSupport {

  private static final Instant T0 = Instant.parse("2026-11-13T01:00:15Z");

  private static DataSource dataSource;

  private final MutableClock clock = new MutableClock(T0);
  private JdbcTemplate jdbc;
  private DataSourceTransactionManager tm;
  private long admin;

  @BeforeAll
  static void migrate() throws Exception {
    dataSource = migratedDatabase("admin_mfa_reencrypt");
  }

  @BeforeEach
  void setUp() {
    jdbc = new JdbcTemplate(dataSource);
    jdbc.update("DELETE FROM job");
    jdbc.update("DELETE FROM admin_grant");
    jdbc.update("DELETE FROM member");
    tm = new DataSourceTransactionManager(dataSource);
    admin = grant();
  }

  @Test
  @DisplayName("옛 버전 비밀값을 새 버전으로 바꾸고, 옛 키를 지운 뒤에도 같은 인증 앱 코드로 로그인한다")
  void reencryptedSecretStillVerifies() {
    AdminMfaStore oldStore = store(TestSecrets.keys());
    byte[] secret = decode(oldStore.enroll(admin).secret());
    assertThat(oldStore.confirm(admin, Totp.code(secret, Totp.step(T0))))
        .isEqualTo(AdminMfaStore.Outcome.OK);
    long notEnrolled = grant();

    KeyReencryptor.Result result = new AdminMfaReencryptor(jdbc, TestSecrets.rotated()).reencrypt();

    assertThat(result).isEqualTo(new KeyReencryptor.Result(1, 0));
    assertThat(
            jdbc.queryForObject(
                "SELECT mfa_key_version FROM admin_grant WHERE member_id = ?",
                Integer.class,
                admin))
        .isEqualTo(2);
    assertThat(
            jdbc.queryForObject(
                "SELECT mfa_secret IS NULL FROM admin_grant WHERE member_id = ?",
                Boolean.class,
                notEnrolled))
        .isTrue();
    clock.set(T0.plusSeconds(30));
    assertThat(
            store(TestSecrets.rotatedWithoutOld())
                .verify(admin, Totp.code(secret, Totp.step(clock.instant()))))
        .isEqualTo(AdminMfaStore.Outcome.OK);
    assertThat(new AdminMfaReencryptor(jdbc, TestSecrets.rotated()).reencrypt())
        .isEqualTo(new KeyReencryptor.Result(0, 0));
  }

  @Test
  @DisplayName("등록 확인 전 비밀값도 다시 암호화한다")
  void reencryptsPendingEnrollment() {
    byte[] secret = decode(store(TestSecrets.keys()).enroll(admin).secret());

    assertThat(new AdminMfaReencryptor(jdbc, TestSecrets.rotated()).reencrypt())
        .isEqualTo(new KeyReencryptor.Result(1, 0));
    assertThat(
            store(TestSecrets.rotatedWithoutOld()).confirm(admin, Totp.code(secret, Totp.step(T0))))
        .isEqualTo(AdminMfaStore.Outcome.OK);
  }

  @Test
  @DisplayName("풀 수 없는 비밀값은 건너뛰고 남은 수에 세며, 다른 관리자의 비밀값은 계속 다시 암호화한다")
  void skipsUnreadableSecret() {
    store(TestSecrets.keys()).enroll(admin);
    long broken = grant();
    jdbc.update(
        "UPDATE admin_grant SET mfa_secret = ?, mfa_key_version = 1 WHERE member_id = ?",
        new byte[40],
        broken);

    assertThat(new AdminMfaReencryptor(jdbc, TestSecrets.rotated()).reencrypt())
        .isEqualTo(new KeyReencryptor.Result(1, 1));
    assertThat(
            jdbc.queryForObject(
                "SELECT mfa_key_version FROM admin_grant WHERE member_id = ?",
                Integer.class,
                admin))
        .isEqualTo(2);
  }

  private AdminMfaStore store(SecretKeys keys) {
    return new AdminMfaStore(
        jdbc,
        tm,
        keys,
        new JobStore(jdbc, tm, clock, new JobProperties(5, Duration.ofMinutes(5))),
        clock);
  }

  private long grant() {
    String publicId = UUID.randomUUID().toString();
    jdbc.update(
        "INSERT INTO member (public_id, state, created_at) VALUES (UUID_TO_BIN(?), 'ACTIVE', NOW(6))",
        publicId);
    long id =
        jdbc.queryForObject(
            "SELECT id FROM member WHERE public_id = UUID_TO_BIN(?)", Long.class, publicId);
    jdbc.update(
        "INSERT INTO admin_grant (member_id, role, granted_at) VALUES (?, 'ADMIN', NOW(6))", id);
    return id;
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
