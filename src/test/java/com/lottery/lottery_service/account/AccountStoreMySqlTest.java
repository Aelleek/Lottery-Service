package com.lottery.lottery_service.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lottery.lottery_service.auth.OAuthProvider;
import com.lottery.lottery_service.support.MySqlContainerSupport;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
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

/** {@link AccountStore}가 소셜 계정을 제공자 ID로만 회원과 잇는지 MySQL에서 확인한다. */
class AccountStoreMySqlTest extends MySqlContainerSupport {

  private static final Instant T0 = Instant.parse("2026-11-06T10:00:00Z");

  private static DataSource dataSource;

  private JdbcTemplate jdbc;
  private AccountStore store;

  @BeforeAll
  static void migrate() throws Exception {
    dataSource = migratedDatabase("account_store");
  }

  @BeforeEach
  void setUp() {
    jdbc = new JdbcTemplate(dataSource);
    jdbc.update("DELETE FROM oauth_account");
    jdbc.update("DELETE FROM actor_subject");
    jdbc.update("DELETE FROM member");
    store = storeAt(T0);
  }

  @Test
  @DisplayName("처음 보는 소셜 계정이면 회원, 회원 주체, 연결을 만들고 마지막 로그인 시각을 남긴다")
  void firstLoginRegisters() {
    MemberLogin login = store.login(OAuthProvider.KAKAO, "4433749540");

    assertThat(login.newMember()).isTrue();
    assertThat(login.authVersion()).isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM actor_subject WHERE id = ? AND member_id = ?"
                    + " AND kind = 'MEMBER' AND state = 'ACTIVE'",
                Long.class,
                login.subjectId(),
                login.memberId()))
        .isEqualTo(1);
    assertThat(lastLogin(login.memberId())).isEqualTo(T0);
  }

  @Test
  @DisplayName("같은 소셜 계정으로 다시 로그인하면 같은 회원이고 마지막 로그인 시각만 바뀐다")
  void returningLoginKeepsMember() {
    MemberLogin first = store.login(OAuthProvider.GOOGLE, "sub-1");
    Instant later = T0.plusSeconds(3600);

    MemberLogin second = storeAt(later).login(OAuthProvider.GOOGLE, "sub-1");

    assertThat(second.newMember()).isFalse();
    assertThat(second.memberId()).isEqualTo(first.memberId());
    assertThat(second.subjectPublicId()).isEqualTo(first.subjectPublicId());
    assertThat(lastLogin(first.memberId())).isEqualTo(later);
    assertThat(count("member")).isEqualTo(1);
  }

  @Test
  @DisplayName("제공자가 다르면 같은 사용자 ID여도 다른 회원이다. 이메일로 합치는 경로가 없다")
  void noMergeAcrossProviders() {
    MemberLogin kakao = store.login(OAuthProvider.KAKAO, "1");
    MemberLogin naver = store.login(OAuthProvider.NAVER, "1");
    MemberLogin google = store.login(OAuthProvider.GOOGLE, "1");

    assertThat(List.of(kakao.memberId(), naver.memberId(), google.memberId()))
        .doesNotHaveDuplicates();
    assertThat(count("member")).isEqualTo(3);
  }

  @Test
  @DisplayName("제공자 사용자 ID는 대소문자까지 그대로 비교한다")
  void providerUserIdIsCaseSensitive() {
    MemberLogin lower = store.login(OAuthProvider.NAVER, "abc");
    MemberLogin upper = store.login(OAuthProvider.NAVER, "ABC");

    assertThat(lower.memberId()).isNotEqualTo(upper.memberId());
  }

  @Test
  @DisplayName("빈 제공자 사용자 ID는 받지 않는다")
  void blankProviderUserIdRejected() {
    assertThatThrownBy(() -> store.login(OAuthProvider.KAKAO, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> store.login(OAuthProvider.KAKAO, " "))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(count("member")).isZero();
  }

  @Test
  @DisplayName("같은 소셜 계정의 첫 로그인이 동시에 와도 회원은 하나다")
  void concurrentFirstLoginCreatesOneMember() throws Exception {
    CountDownLatch start = new CountDownLatch(1);
    List<CompletableFuture<MemberLogin>> logins = new ArrayList<>();
    for (int i = 0; i < 4; i++) {
      logins.add(
          CompletableFuture.supplyAsync(
              () -> {
                try {
                  start.await();
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                }
                return store.login(OAuthProvider.KAKAO, "race");
              }));
    }
    start.countDown();

    List<Long> members = new ArrayList<>();
    for (CompletableFuture<MemberLogin> f : logins) {
      members.add(f.get(20, TimeUnit.SECONDS).memberId());
    }
    assertThat(members).containsOnly(members.get(0));
    assertThat(count("member")).isEqualTo(1);
    assertThat(count("actor_subject")).isEqualTo(1);
  }

  @Test
  @DisplayName("탈퇴 처리 중인 회원은 로그인할 수 없고 마지막 로그인 시각도 바뀌지 않는다")
  void withdrawingMemberBlocked() {
    MemberLogin login = store.login(OAuthProvider.KAKAO, "7");
    jdbc.update(
        "UPDATE member SET state = 'WITHDRAWING', last_login_at = NULL WHERE id = ?",
        login.memberId());

    assertThatThrownBy(() -> storeAt(T0.plusSeconds(60)).login(OAuthProvider.KAKAO, "7"))
        .isInstanceOf(AccountBlockedException.class);
    assertThat(lastLogin(login.memberId())).isNull();
  }

  @Test
  @DisplayName("탈퇴가 끝나 연결이 지워진 소셜 계정으로 다시 오면 새 회원과 새 주체다(T39)")
  void rejoinAfterWithdrawalIsNewMember() {
    MemberLogin before = store.login(OAuthProvider.GOOGLE, "sub-9");
    jdbc.update("DELETE FROM oauth_account WHERE member_id = ?", before.memberId());
    jdbc.update(
        "UPDATE member SET state = 'WITHDRAWN', withdrawn_at = NOW(6) WHERE id = ?",
        before.memberId());
    jdbc.update("UPDATE actor_subject SET state = 'WITHDRAWN' WHERE id = ?", before.subjectId());

    MemberLogin after = store.login(OAuthProvider.GOOGLE, "sub-9");

    assertThat(after.newMember()).isTrue();
    assertThat(after.memberId()).isNotEqualTo(before.memberId());
    assertThat(after.memberPublicId()).isNotEqualTo(before.memberPublicId());
    assertThat(after.subjectId()).isNotEqualTo(before.subjectId());
  }

  private AccountStore storeAt(Instant now) {
    return new AccountStore(
        jdbc, new DataSourceTransactionManager(dataSource), Clock.fixed(now, ZoneOffset.UTC));
  }

  private Instant lastLogin(long memberId) {
    LocalDateTime value =
        jdbc.queryForObject(
            "SELECT last_login_at FROM member WHERE id = ?", LocalDateTime.class, memberId);
    return value == null ? null : value.toInstant(ZoneOffset.UTC);
  }

  private long count(String table) {
    return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
  }
}
