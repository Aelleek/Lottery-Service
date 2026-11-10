package com.lottery.lottery_service.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.lottery.lottery_service.account.AccountStore;
import com.lottery.lottery_service.account.GuestCookieProperties;
import com.lottery.lottery_service.account.GuestCredential;
import com.lottery.lottery_service.account.GuestCredentialStore;
import com.lottery.lottery_service.account.GuestIssueRateLimiter;
import com.lottery.lottery_service.account.GuestIssueService;
import com.lottery.lottery_service.account.MemberLogin;
import com.lottery.lottery_service.account.SubjectClaimStore;
import com.lottery.lottery_service.support.MySqlContainerSupport;
import com.lottery.lottery_service.support.TestSecrets;
import jakarta.servlet.http.Cookie;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;

/** 로그인 성공 때 비회원 주체를 귀속하고 비회원 쿠키를 지우는지 MySQL에서 확인한다(05 4절). */
class LoginSuccessHandlerMySqlTest extends MySqlContainerSupport {

  private static DataSource dataSource;

  private final Clock clock = Clock.fixed(Instant.parse("2026-11-08T14:00:00Z"), ZoneOffset.UTC);
  private JdbcTemplate jdbc;
  private GuestCredentialStore credentials;
  private AccountStore accounts;
  private LoginSuccessHandler handler;

  @BeforeAll
  static void migrate() throws Exception {
    dataSource = migratedDatabase("login_success");
  }

  @BeforeEach
  void setUp() {
    jdbc = new JdbcTemplate(dataSource);
    jdbc.update("DELETE FROM subject_claim");
    jdbc.update("DELETE FROM guest_credential");
    jdbc.update("DELETE FROM oauth_account");
    jdbc.update("DELETE FROM actor_subject");
    jdbc.update("DELETE FROM member");
    DataSourceTransactionManager tm = new DataSourceTransactionManager(dataSource);
    credentials = new GuestCredentialStore(jdbc, tm, clock, TestSecrets.keys());
    accounts = new AccountStore(jdbc, tm, clock);
    GuestIssueService issuer =
        new GuestIssueService(
            credentials, new GuestIssueRateLimiter(clock), clock, GuestCookieProperties.defaults());
    handler =
        new LoginSuccessHandler(credentials, issuer, new SubjectClaimStore(jdbc, tm, clock), clock);
  }

  @Test
  @DisplayName("유효한 비회원 쿠키로 로그인하면 귀속을 만들고 쿠키를 지운 뒤 첫 화면으로 보낸다")
  void claimsGuestOnLogin() throws Exception {
    GuestCredential guest = credentials.issue();
    MemberLogin login = accounts.login(OAuthProvider.NAVER, "naver-1");
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/login/oauth2/code/naver");
    request.setCookies(new Cookie("__Host-lottery_guest", guest.token()));
    MockHttpServletResponse response = new MockHttpServletResponse();

    handler.onAuthenticationSuccess(request, response, auth(login));

    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM subject_claim WHERE guest_subject_id = ?"
                    + " AND member_subject_id = ?",
                Long.class,
                guest.subjectId(),
                login.subjectId()))
        .isEqualTo(1);
    assertThat(response.getHeader("Set-Cookie"))
        .startsWith("__Host-lottery_guest=;")
        .contains("Max-Age=0");
    assertThat(response.getRedirectedUrl()).isEqualTo("/");
  }

  @Test
  @DisplayName("비회원 쿠키가 없거나 유효하지 않아도 로그인은 그대로 끝난다")
  void loginWithoutGuest() throws Exception {
    MemberLogin login = accounts.login(OAuthProvider.NAVER, "naver-2");
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/login/oauth2/code/naver");
    request.setCookies(new Cookie("__Host-lottery_guest", "not-a-token"));
    MockHttpServletResponse response = new MockHttpServletResponse();

    handler.onAuthenticationSuccess(request, response, auth(login));

    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM subject_claim", Long.class)).isZero();
    assertThat(response.getRedirectedUrl()).isEqualTo("/");
    Instant loggedInAt = clock.instant();
    assertThat(
            SessionLifetime.expired(
                request.getSession(false), loggedInAt.plus(SessionLifetime.MAX).minusSeconds(1)))
        .isFalse();
    assertThat(
            SessionLifetime.expired(
                request.getSession(false), loggedInAt.plus(SessionLifetime.MAX)))
        .isTrue();
  }

  private static OAuth2AuthenticationToken auth(MemberLogin login) {
    MemberPrincipal principal = MemberPrincipal.of(login, OAuthProvider.NAVER);
    return new OAuth2AuthenticationToken(principal, principal.getAuthorities(), "naver");
  }
}
