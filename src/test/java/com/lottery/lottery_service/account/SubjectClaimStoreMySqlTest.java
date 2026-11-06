package com.lottery.lottery_service.account;

import static org.assertj.core.api.Assertions.assertThat;

import com.lottery.lottery_service.auth.OAuthProvider;
import com.lottery.lottery_service.support.MySqlContainerSupport;
import com.lottery.lottery_service.support.TestSecrets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/** {@link SubjectClaimStore}가 비회원 주체를 한 회원에게만 귀속하는지 MySQL에서 확인한다. */
class SubjectClaimStoreMySqlTest extends MySqlContainerSupport {

  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-11-06T10:00:00Z"), ZoneOffset.UTC);

  private static DataSource dataSource;

  private JdbcTemplate jdbc;
  private SubjectClaimStore claims;
  private GuestCredentialStore guests;
  private AccountStore accounts;

  @BeforeAll
  static void migrate() throws Exception {
    dataSource = migratedDatabase("subject_claim");
  }

  @BeforeEach
  void setUp() {
    jdbc = new JdbcTemplate(dataSource);
    for (String table :
        List.of("subject_claim", "guest_credential", "oauth_account", "actor_subject", "member")) {
      jdbc.update("DELETE FROM " + table);
    }
    DataSourceTransactionManager tm = new DataSourceTransactionManager(dataSource);
    claims = new SubjectClaimStore(jdbc, tm, CLOCK);
    guests = new GuestCredentialStore(jdbc, tm, CLOCK, TestSecrets.keys());
    accounts = new AccountStore(jdbc, tm, CLOCK);
  }

  @Test
  @DisplayName("귀속하면 귀속 행이 생기고 비회원 주체는 CLAIMED, 두 주체의 귀속 버전이 오른다")
  void claim() {
    long guest = guests.issue().subjectId();
    long member = accounts.login(OAuthProvider.KAKAO, "1").subjectId();

    assertThat(claims.claim(guest, member)).isEqualTo(ClaimResult.CLAIMED);

    assertThat(state(guest)).isEqualTo("CLAIMED");
    assertThat(claimVersion(guest)).isEqualTo(1);
    assertThat(claimVersion(member)).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT member_subject_id FROM subject_claim WHERE guest_subject_id = ?"
                    + " AND imported_at IS NULL",
                Long.class,
                guest))
        .isEqualTo(member);
  }

  @Test
  @DisplayName("같은 회원이 다시 귀속하면 그대로 두고, 다른 회원은 귀속할 수 없다")
  void claimOnlyOnce() {
    long guest = guests.issue().subjectId();
    long first = accounts.login(OAuthProvider.KAKAO, "1").subjectId();
    long second = accounts.login(OAuthProvider.NAVER, "2").subjectId();
    claims.claim(guest, first);

    assertThat(claims.claim(guest, first)).isEqualTo(ClaimResult.ALREADY_CLAIMED);
    assertThat(claims.claim(guest, second)).isEqualTo(ClaimResult.UNAVAILABLE);
    assertThat(claimVersion(guest)).isEqualTo(1);
    assertThat(claimVersion(second)).isZero();
  }

  @Test
  @DisplayName("두 회원이 같은 비회원 주체를 동시에 귀속하면 한 명만 성공한다(T13 일부)")
  void concurrentClaim() throws Exception {
    long guest = guests.issue().subjectId();
    long a = accounts.login(OAuthProvider.KAKAO, "a").subjectId();
    long b = accounts.login(OAuthProvider.KAKAO, "b").subjectId();
    CountDownLatch start = new CountDownLatch(1);

    CompletableFuture<ClaimResult> ra = CompletableFuture.supplyAsync(() -> after(start, guest, a));
    CompletableFuture<ClaimResult> rb = CompletableFuture.supplyAsync(() -> after(start, guest, b));
    start.countDown();

    assertThat(List.of(ra.get(10, TimeUnit.SECONDS), rb.get(10, TimeUnit.SECONDS)))
        .containsExactlyInAnyOrder(ClaimResult.CLAIMED, ClaimResult.UNAVAILABLE);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM subject_claim", Long.class)).isEqualTo(1);
  }

  @Test
  @DisplayName("주체 종류가 맞지 않거나 회원이 탈퇴 처리 중이면 귀속하지 않는다")
  void invalidSubjects() {
    long guest = guests.issue().subjectId();
    long otherGuest = guests.issue().subjectId();
    MemberLogin member = accounts.login(OAuthProvider.GOOGLE, "g");

    assertThat(claims.claim(guest, otherGuest)).isEqualTo(ClaimResult.UNAVAILABLE);
    assertThat(claims.claim(member.subjectId(), guest)).isEqualTo(ClaimResult.UNAVAILABLE);
    assertThat(claims.claim(guest, guest)).isEqualTo(ClaimResult.UNAVAILABLE);
    assertThat(claims.claim(guest, 999_999)).isEqualTo(ClaimResult.UNAVAILABLE);

    jdbc.update("UPDATE actor_subject SET state = 'WITHDRAWING' WHERE id = ?", member.subjectId());
    assertThat(claims.claim(guest, member.subjectId())).isEqualTo(ClaimResult.UNAVAILABLE);
    assertThat(state(guest)).isEqualTo("ACTIVE");
  }

  private ClaimResult after(CountDownLatch start, long guest, long member) {
    try {
      start.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    return claims.claim(guest, member);
  }

  private String state(long subject) {
    return jdbc.queryForObject(
        "SELECT state FROM actor_subject WHERE id = ?", String.class, subject);
  }

  private long claimVersion(long subject) {
    return jdbc.queryForObject(
        "SELECT claim_version FROM actor_subject WHERE id = ?", Long.class, subject);
  }
}
