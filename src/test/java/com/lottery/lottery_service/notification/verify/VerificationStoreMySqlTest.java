package com.lottery.lottery_service.notification.verify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lottery.lottery_service.account.AccountStore;
import com.lottery.lottery_service.account.MemberLogin;
import com.lottery.lottery_service.auth.OAuthProvider;
import com.lottery.lottery_service.common.command.CommandOperationStore;
import com.lottery.lottery_service.common.command.CommandTokenCodec;
import com.lottery.lottery_service.common.error.BusinessException;
import com.lottery.lottery_service.common.error.ErrorCode;
import com.lottery.lottery_service.support.MutableClock;
import com.lottery.lottery_service.support.MySqlContainerSupport;
import com.lottery.lottery_service.support.TestSecrets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 인증 코드 발급·확인과 발급 제한을 MySQL에서 확인한다. */
class VerificationStoreMySqlTest extends MySqlContainerSupport {

  private static final String PHONE = "01012345678";

  private static DataSource dataSource;

  private final MutableClock clock = new MutableClock(Instant.parse("2026-11-08T01:00:00Z"));
  private JdbcTemplate jdbc;
  private TransactionTemplate tx;
  private VerificationStore store;
  private ContactCipher cipher;
  private long member;
  private long other;

  @BeforeAll
  static void migrate() throws Exception {
    dataSource = migratedDatabase("verification");
  }

  @BeforeEach
  void setUp() {
    jdbc = new JdbcTemplate(dataSource);
    for (String t :
        List.of(
            "command_operation",
            "verification_rate_bucket",
            "verification_challenge",
            "member_contact",
            "consent_event",
            "oauth_account",
            "actor_subject",
            "member")) {
      jdbc.update("DELETE FROM " + t);
    }
    DataSourceTransactionManager tm = new DataSourceTransactionManager(dataSource);
    tx = new TransactionTemplate(tm);
    cipher = new ContactCipher(TestSecrets.keys());
    store = new VerificationStore(jdbc, TestSecrets.keys(), cipher, clock);
    AccountStore accounts = new AccountStore(jdbc, tm, clock);
    member = memberOf(accounts.login(OAuthProvider.KAKAO, "a").subjectId());
    other = memberOf(accounts.login(OAuthProvider.KAKAO, "b").subjectId());
  }

  @Test
  @DisplayName("맞는 코드면 번호를 암호화해 인증하고 수신은 꺼 둔다. 같은 코드는 다시 쓸 수 없다")
  void verify() {
    IssuedChallenge c = issue(member);

    assertThat(c.code()).matches("\\d{6}");
    assertThat(c.toString()).doesNotContain(c.code());
    assertThat(verify(member, c, c.code())).isEqualTo(VerificationResult.VERIFIED);
    assertThat(verify(member, c, c.code())).isEqualTo(VerificationResult.EXPIRED);
    byte[] stored =
        jdbc.queryForObject(
            "SELECT address_ciphertext FROM member_contact WHERE member_id = ?",
            byte[].class,
            member);
    assertThat(cipher.decrypt(member, stored)).isEqualTo(PHONE);
    assertThat(jdbc.queryForObject("SELECT notify_enabled FROM member_contact", Boolean.class))
        .isFalse();
    assertThat(
            jdbc.queryForObject(
                "SELECT code_hmac IS NULL FROM verification_challenge", Boolean.class))
        .isTrue();
  }

  @Test
  @DisplayName("오답 5회면 잠기고, 그 뒤 맞는 코드도 받지 않는다. 시도 수는 매번 커밋된다(T29)")
  void lockAfterFiveWrongAttempts() {
    IssuedChallenge c = issue(member);
    String wrong = c.code().equals("000000") ? "111111" : "000000";
    for (int i = 0; i < 4; i++) {
      assertThat(verify(member, c, wrong)).isEqualTo(VerificationResult.WRONG);
    }

    assertThat(verify(member, c, wrong)).isEqualTo(VerificationResult.LOCKED);
    assertThat(verify(member, c, c.code())).isEqualTo(VerificationResult.LOCKED);
    assertThat(jdbc.queryForObject("SELECT attempts FROM verification_challenge", Integer.class))
        .isEqualTo(5);
  }

  @Test
  @DisplayName("다시 발급하면 이전 코드는 무효, 5분이 지나면 만료, 연락처 버전이 바뀌면 거부")
  void reissueExpiryAndContactVersion() {
    IssuedChallenge first = issue(member);
    clock.set(clock.instant().plusSeconds(60));
    IssuedChallenge second = issue(member);

    assertThat(verify(member, first, first.code())).isEqualTo(VerificationResult.EXPIRED);
    assertThat(
            Boolean.TRUE.equals(
                tx.execute(
                    s ->
                        store.verify(member, second.publicId(), second.code(), 9)
                            == VerificationResult.CONTACT_CHANGED)))
        .isTrue();
    clock.set(second.expiresAt());
    assertThat(verify(member, second, second.code())).isEqualTo(VerificationResult.EXPIRED);
  }

  @Test
  @DisplayName("재발급은 60초 간격, 하루 5회. 다른 계정이라도 같은 번호면 번호 한도에 걸린다")
  void rateLimits() {
    issue(member);
    assertLimited(() -> issue(member), clock.instant().plusSeconds(60));
    for (int i = 0; i < 4; i++) {
      clock.set(clock.instant().plusSeconds(60));
      issue(member);
    }
    clock.set(clock.instant().plusSeconds(60));
    assertLimited(() -> issue(member), Instant.parse("2026-11-08T15:00:00Z"));
    assertLimited(() -> issue(other), Instant.parse("2026-11-08T15:00:00Z"));

    clock.set(Instant.parse("2026-11-08T15:00:00Z"));
    assertThat(issue(member)).isNotNull();
  }

  @Test
  @DisplayName("인증 전에는 동의할 수 없고, 인증 뒤 동의하면 수신이 켜지며, 철회하거나 번호를 다시 인증하면 꺼진다")
  void consent() {
    ConsentStore consents = new ConsentStore(jdbc, clock);
    assertThatThrownBy(() -> tx.executeWithoutResult(s -> consents.grant(member, "v1", 0)))
        .isInstanceOf(BusinessException.class);
    IssuedChallenge c = issue(member);
    verify(member, c, c.code());

    assertThatThrownBy(() -> tx.executeWithoutResult(s -> consents.grant(member, "v1", 5)))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.getCode()).isEqualTo(ErrorCode.CONTACT_CHANGED));
    tx.executeWithoutResult(s -> consents.grant(member, "v1", 0));
    assertThat(jdbc.queryForObject("SELECT notify_enabled FROM member_contact", Boolean.class))
        .isTrue();

    tx.executeWithoutResult(s -> consents.withdraw(member, "v1"));
    assertThat(jdbc.queryForObject("SELECT notify_enabled FROM member_contact", Boolean.class))
        .isFalse();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM consent_event", Long.class)).isEqualTo(2);

    tx.executeWithoutResult(s -> consents.grant(member, "v1", 2));
    clock.set(clock.instant().plusSeconds(60));
    IssuedChallenge again = tx.execute(s -> store.issue(member, "01099998888", 3));
    tx.execute(s -> store.verify(member, again.publicId(), again.code(), 3));
    assertThat(jdbc.queryForObject("SELECT notify_enabled FROM member_contact", Boolean.class))
        .isFalse();
  }

  @Test
  @DisplayName(
      "확인 명령: 틀린 코드는 시도 수를 커밋한 뒤 VALIDATION_FAILED, 같은 키 재전송은 같은 실패이고 시도 수가 늘지 않으며, 맞는 코드는 인증된 연락처 버전")
  void verifyCommand() {
    DataSourceTransactionManager tm = new DataSourceTransactionManager(dataSource);
    MemberLogin login = new AccountStore(jdbc, tm, clock).login(OAuthProvider.KAKAO, "c");
    CommandTokenCodec codec = new CommandTokenCodec(TestSecrets.keys(), clock);
    VerifyCommandService commands =
        new VerifyCommandService(
            codec, new CommandOperationStore(jdbc, tm, clock), store, jdbc, tm);
    String token = codec.issue(login.subjectPublicId(), VerifyCommandService.OPERATION, null, null);
    IssuedChallenge c = issue(login.memberId());
    String wrong = c.code().equals("000000") ? "111111" : "000000";
    UUID wrongKey = UUID.randomUUID();

    for (int i = 0; i < 2; i++) {
      assertThatThrownBy(
              () ->
                  commands.verify(
                      login.memberId(),
                      login.subjectId(),
                      login.subjectPublicId(),
                      token,
                      wrongKey,
                      c.publicId(),
                      wrong))
          .isInstanceOfSatisfying(
              BusinessException.class,
              e -> assertThat(e.getCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }
    assertThat(
            jdbc.queryForObject(
                "SELECT attempts FROM verification_challenge WHERE id = ?",
                Integer.class,
                c.challengeId()))
        .isEqualTo(1);

    long version =
        commands.verify(
            login.memberId(),
            login.subjectId(),
            login.subjectPublicId(),
            token,
            UUID.randomUUID(),
            c.publicId(),
            c.code());
    assertThat(version)
        .isEqualTo(
            jdbc.queryForObject(
                "SELECT version FROM member_contact WHERE member_id = ?",
                Long.class,
                login.memberId()));
  }

  @Test
  @DisplayName("다른 회원의 요청 ID로는 확인할 수 없고, 원래 회원의 요청은 그대로 남는다")
  void otherMemberCannotVerify() {
    IssuedChallenge c = issue(member);

    assertThat(verify(other, c, c.code())).isEqualTo(VerificationResult.EXPIRED);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM member_contact WHERE member_id = ?", Long.class, other))
        .isZero();
    assertThat(verify(member, c, c.code())).isEqualTo(VerificationResult.VERIFIED);
  }

  private IssuedChallenge issue(long memberId) {
    return tx.execute(s -> store.issue(memberId, PHONE, 0));
  }

  private VerificationResult verify(long memberId, IssuedChallenge c, String code) {
    return tx.execute(s -> store.verify(memberId, c.publicId(), code, 0));
  }

  private void assertLimited(Supplier<IssuedChallenge> call, Instant retryAt) {
    assertThatThrownBy(call::get)
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> {
              assertThat(e.getCode()).isEqualTo(ErrorCode.RATE_LIMITED);
              assertThat(e.getDetails().retryAt()).isEqualTo(retryAt);
            });
  }

  private long memberOf(long subject) {
    return jdbc.queryForObject(
        "SELECT member_id FROM actor_subject WHERE id = ?", Long.class, subject);
  }
}
