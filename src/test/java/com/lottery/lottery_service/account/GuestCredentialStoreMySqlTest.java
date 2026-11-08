package com.lottery.lottery_service.account;

import static org.assertj.core.api.Assertions.assertThat;

import com.lottery.lottery_service.common.secret.SecretKeys;
import com.lottery.lottery_service.common.secret.SecretProperties;
import com.lottery.lottery_service.support.MutableClock;
import com.lottery.lottery_service.support.MySqlContainerSupport;
import com.lottery.lottery_service.support.TestSecrets;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/** {@link GuestCredentialStore}의 발급, 확인, 회전을 MySQL에서 확인한다. */
class GuestCredentialStoreMySqlTest extends MySqlContainerSupport {

  private static final Instant T0 = Instant.parse("2026-11-06T10:00:00Z");

  private static DataSource dataSource;

  private final MutableClock clock = new MutableClock(T0);
  private JdbcTemplate jdbc;
  private GuestCredentialStore store;

  @BeforeAll
  static void migrate() throws Exception {
    dataSource = migratedDatabase("guest_credential");
  }

  @BeforeEach
  void setUp() {
    jdbc = new JdbcTemplate(dataSource);
    jdbc.update("DELETE FROM guest_credential");
    jdbc.update("DELETE FROM actor_subject");
    store =
        new GuestCredentialStore(
            jdbc, new DataSourceTransactionManager(dataSource), clock, TestSecrets.keys());
  }

  @Test
  @DisplayName("발급하면 비회원 주체와 37일짜리 자격이 생기고, DB에는 원문이 아니라 해시만 있다")
  void issueStoresOnlyHash() {
    GuestCredential issued = store.issue();

    assertThat(issued.expiresAt()).isEqualTo(T0.plus(GuestCredentialStore.VALIDITY));
    assertThat(issued.token()).hasSizeGreaterThanOrEqualTo(43);
    assertThat(issued.toString()).doesNotContain(issued.token());
    byte[] stored =
        jdbc.queryForObject(
            "SELECT token_hash FROM guest_credential WHERE subject_id = ?",
            byte[].class,
            issued.subjectId());
    assertThat(stored)
        .hasSize(32)
        .isEqualTo(TestSecrets.keys().guestCredential().sign(issued.token().getBytes()));
    assertThat(
            jdbc.queryForObject(
                "SELECT kind FROM actor_subject WHERE id = ?", String.class, issued.subjectId()))
        .isEqualTo("GUEST");
  }

  @Test
  @DisplayName("유효한 자격은 그 주체를 찾고, 다른 원문이나 빈 값은 찾지 못한다")
  void resolve() {
    GuestCredential a = store.issue();
    GuestCredential b = store.issue();

    assertThat(store.resolve(a.token()))
        .hasValueSatisfying(s -> assertThat(s.subjectId()).isEqualTo(a.subjectId()));
    assertThat(store.resolve(b.token()))
        .hasValueSatisfying(s -> assertThat(s.subjectId()).isEqualTo(b.subjectId()));
    assertThat(store.resolve(a.token() + "x")).isEmpty();
    assertThat(store.resolve("")).isEmpty();
    assertThat(store.resolve(null)).isEmpty();
  }

  @Test
  @DisplayName("만료 시각부터는 자격을 받지 않는다")
  void expiredCredentialRejected() {
    GuestCredential issued = store.issue();

    clock.set(issued.expiresAt().minusNanos(1000));
    assertThat(store.resolve(issued.token())).isPresent();
    clock.set(issued.expiresAt());
    assertThat(store.resolve(issued.token())).isEmpty();
  }

  @Test
  @DisplayName("자격을 바꾸면 같은 주체에 새 37일 자격이 생기고 옛 자격은 쓸 수 없다")
  void rotateKeepsSubject() {
    GuestCredential first = store.issue();
    clock.set(T0.plusSeconds(86_400));

    GuestCredential second = store.rotate(first.token()).orElseThrow();

    assertThat(second.subjectId()).isEqualTo(first.subjectId());
    assertThat(second.subjectPublicId()).isEqualTo(first.subjectPublicId());
    assertThat(second.token()).isNotEqualTo(first.token());
    assertThat(second.expiresAt())
        .isEqualTo(T0.plusSeconds(86_400).plus(GuestCredentialStore.VALIDITY));
    assertThat(store.resolve(first.token())).isEmpty();
    assertThat(store.resolve(second.token())).isPresent();
    assertThat(store.rotate(first.token())).isEmpty();
  }

  @Test
  @DisplayName("다른 계정에 귀속된 비회원 주체는 옛 자격으로 찾지 못한다(T32 일부)")
  void claimedSubjectNotResolved() {
    GuestCredential issued = store.issue();
    jdbc.update("UPDATE actor_subject SET state = 'CLAIMED' WHERE id = ?", issued.subjectId());

    assertThat(store.resolve(issued.token())).isEmpty();
    assertThat(store.rotate(issued.token())).isEmpty();
  }

  @Test
  @DisplayName("키를 새 버전으로 바꿔도 옛 키로 만든 자격을 찾고, 다른 키로는 찾지 못한다")
  void keyRotation() {
    GuestCredential issued = store.issue();
    String oldKey =
        Base64.getEncoder().encodeToString("guest-credential-test-key-012345".getBytes());
    String newKey =
        Base64.getEncoder().encodeToString("guest-credential-next-key-678901".getBytes());
    SecretKeys rotated =
        new SecretKeys(
            new SecretProperties(
                null, new SecretProperties.Ring(2, Map.of(1, oldKey, 2, newKey)), null));
    SecretKeys unrelated = TestSecrets.keys("x".repeat(32), "another-guest-key-00000000000000");
    DataSourceTransactionManager tm = new DataSourceTransactionManager(dataSource);

    assertThat(new GuestCredentialStore(jdbc, tm, clock, rotated).resolve(issued.token()))
        .isPresent();
    assertThat(new GuestCredentialStore(jdbc, tm, clock, unrelated).resolve(issued.token()))
        .isEmpty();
  }
}
