package com.lottery.lottery_service.notification.verify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lottery.lottery_service.account.AccountStore;
import com.lottery.lottery_service.auth.OAuthProvider;
import com.lottery.lottery_service.common.secret.KeyReencryptor;
import com.lottery.lottery_service.support.MutableClock;
import com.lottery.lottery_service.support.MySqlContainerSupport;
import com.lottery.lottery_service.support.TestSecrets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 연락처 키를 바꾼 뒤 남은 수신 번호 암호문을 새 버전으로 다시 암호화하는지 MySQL에서 확인한다(03 6.6, C-30). */
class ContactReencryptorMySqlTest extends MySqlContainerSupport {

  private static final String PHONE = "+821012345678";

  private static DataSource dataSource;

  private final MutableClock clock = new MutableClock(Instant.parse("2026-11-13T01:00:00Z"));
  private final ContactCipher oldCipher = new ContactCipher(TestSecrets.keys());
  private final ContactCipher newCipher = new ContactCipher(TestSecrets.rotated());
  private final ContactCipher onlyNew = new ContactCipher(TestSecrets.rotatedWithoutOld());
  private JdbcTemplate jdbc;
  private TransactionTemplate tx;
  private ContactReencryptor reencryptor;
  private long member;
  private long other;

  @BeforeAll
  static void migrate() throws Exception {
    dataSource = migratedDatabase("contact_reencrypt");
  }

  @BeforeEach
  void setUp() {
    jdbc = new JdbcTemplate(dataSource);
    for (String t :
        List.of(
            "delivery_message",
            "verification_rate_bucket",
            "verification_challenge",
            "member_contact",
            "lotto_round",
            "oauth_account",
            "actor_subject",
            "member")) {
      jdbc.update("DELETE FROM " + t);
    }
    DataSourceTransactionManager tm = new DataSourceTransactionManager(dataSource);
    tx = new TransactionTemplate(tm);
    AccountStore accounts = new AccountStore(jdbc, tm, clock);
    member = memberOf(accounts.login(OAuthProvider.KAKAO, "a").subjectId());
    other = memberOf(accounts.login(OAuthProvider.KAKAO, "b").subjectId());
    reencryptor = new ContactReencryptor(jdbc, newCipher);
  }

  @Test
  @DisplayName("옛 버전 연락처·인증 요청·메시지 사본을 새 버전으로 바꾸고, 그 뒤에는 옛 키 없이도 풀린다")
  void reencryptsEveryCopy() {
    long challenge = verifiedWithOldKey();
    long verificationMessage = message("VERIFICATION", challenge, oldCipher.encrypt(member, PHONE));
    long resultMessage = resultMessage(oldCipher.encrypt(member, PHONE), 1);
    byte[] current = newCipher.encrypt(other, "+821099998888");
    jdbc.update(
        "INSERT INTO member_contact (member_id, address_ciphertext, key_version) VALUES (?, ?, 2)",
        other,
        current);

    KeyReencryptor.Result result = reencryptor.reencrypt();

    assertThat(result).isEqualTo(new KeyReencryptor.Result(4, 0));
    assertThat(onlyNew.decrypt(member, contact(member))).isEqualTo(PHONE);
    assertThat(
            onlyNew.decrypt(
                member, column("verification_challenge", "target_ciphertext", challenge)))
        .isEqualTo(PHONE);
    assertThat(
            onlyNew.decrypt(
                member, column("delivery_message", "recipient_ciphertext", resultMessage)))
        .isEqualTo(PHONE);
    assertThat(
            onlyNew.decrypt(
                member, column("delivery_message", "recipient_ciphertext", verificationMessage)))
        .isEqualTo(PHONE);
    assertThat(contact(other)).isEqualTo(current);
    assertThat(
            jdbc.queryForObject(
                "SELECT key_version FROM member_contact WHERE member_id = ?",
                Integer.class,
                member))
        .isEqualTo(2);
    assertThat(versions(resultMessage)).containsEntry("key_version", 2);
    assertThat(versions(verificationMessage)).containsEntry("key_version", null);
    assertThat(
            jdbc.queryForObject(
                "SELECT key_version FROM verification_challenge WHERE id = ?",
                Integer.class,
                challenge))
        .as("인증 코드 HMAC 키 버전은 그대로")
        .isEqualTo(1);
    assertThatThrownBy(() -> onlyNew.decrypt(member, oldCipher.encrypt(member, PHONE)))
        .isInstanceOf(IllegalArgumentException.class);

    assertThat(reencryptor.reencrypt()).isEqualTo(new KeyReencryptor.Result(0, 0));
  }

  @Test
  @DisplayName("키를 바꾸는 사이에 인증을 마치면 연락처의 키 버전은 암호문을 만든 버전으로 남는다")
  void verifiedContactKeepsCiphertextVersion() {
    verifiedWithOldKey();

    assertThat(
            jdbc.queryForObject(
                "SELECT key_version FROM member_contact WHERE member_id = ?",
                Integer.class,
                member))
        .isEqualTo(1);
    assertThat(ContactCipher.versionOf(contact(member))).isEqualTo(1);
  }

  @Test
  @DisplayName("한 묶음(500행)을 넘는 행도 모두 다시 암호화한다")
  void pagesThroughBatches() {
    for (int i = 0; i < 501; i++) {
      challengeRow(oldCipher.encrypt(member, PHONE), "EXPIRED");
    }

    assertThat(reencryptor.reencrypt()).isEqualTo(new KeyReencryptor.Result(501, 0));
    assertThat(
            jdbc
                .queryForList("SELECT target_ciphertext FROM verification_challenge", byte[].class)
                .stream()
                .filter(c -> ContactCipher.versionOf(c) != 2)
                .count())
        .isZero();
  }

  @Test
  @DisplayName("다시 암호화한 값과 옛 값은 다르고, 원문 번호는 어느 쪽에도 없다")
  void ciphertextChangesWithoutPlaintext() {
    byte[] before = oldCipher.encrypt(member, PHONE);
    jdbc.update(
        "INSERT INTO member_contact (member_id, address_ciphertext, key_version) VALUES (?, ?, 1)",
        member,
        before);

    reencryptor.reencrypt();

    byte[] after = contact(member);
    assertThat(after).isNotEqualTo(before);
    assertThat(new String(after, java.nio.charset.StandardCharsets.ISO_8859_1))
        .doesNotContain("1012345678");
  }

  private long verifiedWithOldKey() {
    VerificationStore issuer = new VerificationStore(jdbc, TestSecrets.keys(), oldCipher, clock);
    VerificationStore verifier =
        new VerificationStore(jdbc, TestSecrets.rotated(), newCipher, clock);
    IssuedChallenge issued = tx.execute(s -> issuer.issue(member, PHONE, 0));
    VerificationResult result =
        tx.execute(s -> verifier.verify(member, issued.publicId(), issued.code(), 0));
    assertThat(result).isEqualTo(VerificationResult.VERIFIED);
    return issued.challengeId();
  }

  private long challengeRow(byte[] target, String state) {
    String publicId = java.util.UUID.randomUUID().toString();
    jdbc.update(
        "INSERT INTO verification_challenge (public_id, member_id, target_hmac, target_ciphertext,"
            + " key_version, expected_contact_version, state, expires_at)"
            + " VALUES (UUID_TO_BIN(?), ?, ?, ?, 1, 0, ?, '2026-11-13 01:05:00')",
        publicId,
        member,
        new byte[32],
        target,
        state);
    return jdbc.queryForObject(
        "SELECT id FROM verification_challenge WHERE public_id = UUID_TO_BIN(?)",
        Long.class,
        publicId);
  }

  private long message(String purpose, long challenge, byte[] recipient) {
    String publicId = java.util.UUID.randomUUID().toString();
    jdbc.update(
        "INSERT INTO delivery_message (public_id, purpose, member_id, challenge_id, state,"
            + " provider_request_key, recipient_ciphertext, expires_at)"
            + " VALUES (UUID_TO_BIN(?), ?, ?, ?, 'DELIVERED', UUID_TO_BIN(UUID()), ?,"
            + " '2026-11-13 01:05:00')",
        publicId,
        purpose,
        member,
        challenge,
        recipient);
    return messageId(publicId);
  }

  private long resultMessage(byte[] recipient, int keyVersion) {
    jdbc.update(
        "INSERT INTO lotto_round (round_no, sales_open_at, sales_close_at, draw_at, schedule_version)"
            + " VALUES (1200, '2026-11-01 00:00:00', '2026-11-07 11:00:00', '2026-11-07 11:35:00', 1)");
    String publicId = java.util.UUID.randomUUID().toString();
    jdbc.update(
        "INSERT INTO delivery_message (public_id, purpose, member_id, round_no, state,"
            + " provider_request_key, recipient_ciphertext, key_version, expires_at)"
            + " VALUES (UUID_TO_BIN(?), 'RESULT', ?, 1200, 'DELIVERED', UUID_TO_BIN(UUID()), ?, ?,"
            + " '2026-11-14 11:35:00')",
        publicId,
        member,
        recipient,
        keyVersion);
    return messageId(publicId);
  }

  private long messageId(String publicId) {
    return jdbc.queryForObject(
        "SELECT id FROM delivery_message WHERE public_id = UUID_TO_BIN(?)", Long.class, publicId);
  }

  private byte[] contact(long memberId) {
    return jdbc.queryForObject(
        "SELECT address_ciphertext FROM member_contact WHERE member_id = ?",
        byte[].class,
        memberId);
  }

  private byte[] column(String table, String column, long id) {
    return jdbc.queryForObject(
        "SELECT " + column + " FROM " + table + " WHERE id = ?", byte[].class, id);
  }

  private Map<String, Object> versions(long message) {
    return jdbc.queryForMap("SELECT key_version FROM delivery_message WHERE id = ?", message);
  }

  private long memberOf(long subject) {
    return jdbc.queryForObject(
        "SELECT member_id FROM actor_subject WHERE id = ?", Long.class, subject);
  }
}
