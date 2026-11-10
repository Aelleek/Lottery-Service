package com.lottery.lottery_service.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lottery.lottery_service.common.error.BusinessException;
import com.lottery.lottery_service.common.error.ErrorCode;
import com.lottery.lottery_service.support.MutableClock;
import com.lottery.lottery_service.support.MySqlContainerSupport;
import com.lottery.lottery_service.support.TestSecrets;
import java.time.Instant;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseCookie;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/** 비회원 자격 발급 절차를 MySQL에서 확인한다. */
class GuestIssueServiceMySqlTest extends MySqlContainerSupport {

  private static final Instant T0 = Instant.parse("2026-11-06T10:00:00Z");

  private static DataSource dataSource;

  private final MutableClock clock = new MutableClock(T0);
  private JdbcTemplate jdbc;
  private GuestIssueService service;

  @BeforeAll
  static void migrate() throws Exception {
    dataSource = migratedDatabase("guest_issue");
  }

  @BeforeEach
  void setUp() {
    jdbc = new JdbcTemplate(dataSource);
    jdbc.update("DELETE FROM guest_credential");
    jdbc.update("DELETE FROM actor_subject");
    service =
        new GuestIssueService(
            new GuestCredentialStore(
                jdbc, new DataSourceTransactionManager(dataSource), clock, TestSecrets.keys()),
            new GuestIssueRateLimiter(clock),
            clock,
            GuestCookieProperties.defaults());
  }

  @Test
  @DisplayName("새로 발급하면 자격 쿠키(Secure, HttpOnly, SameSite=Lax, 37일)를 주고, 유효한 쿠키로 다시 오면 같은 주체를 유지한다")
  void issueAndReuse() {
    GuestIssueService.Outcome.Issued issued =
        (GuestIssueService.Outcome.Issued) service.issue(null, false, "10.0.0.1");
    ResponseCookie cookie = issued.cookie();

    assertThat(cookie.getName()).isEqualTo("__Host-lottery_guest");
    assertThat(cookie.getValue()).isEqualTo(issued.credential().token());
    assertThat(cookie.isSecure()).isTrue();
    assertThat(cookie.isHttpOnly()).isTrue();
    assertThat(cookie.getSameSite()).isEqualTo("Lax");
    assertThat(cookie.getMaxAge()).isEqualTo(GuestCredentialStore.VALIDITY);

    GuestIssueService.Outcome again = service.issue(cookie.getValue(), false, "10.0.0.1");
    assertThat(again).isInstanceOf(GuestIssueService.Outcome.Existing.class);
    assertThat(((GuestIssueService.Outcome.Existing) again).subject().subjectId())
        .isEqualTo(issued.credential().subjectId());
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM actor_subject", Long.class)).isEqualTo(1);

    assertThat(service.issue("not-a-token", false, "10.0.0.1"))
        .isInstanceOf(GuestIssueService.Outcome.Issued.class);
  }

  @Test
  @DisplayName("회원 세션이면 FORBIDDEN, 한 IP의 새 발급이 한도를 넘으면 RATE_LIMITED와 다시 시도할 시각(T40), 다른 IP는 영향 없음")
  void memberSessionAndRateLimit() {
    assertThatThrownBy(() -> service.issue(null, true, "10.0.0.1"))
        .isInstanceOfSatisfying(
            BusinessException.class, e -> assertThat(e.getCode()).isEqualTo(ErrorCode.FORBIDDEN));
    for (int i = 0; i < GuestIssueRateLimiter.LIMIT; i++) {
      service.issue(null, false, "10.0.0.2");
    }

    assertThatThrownBy(() -> service.issue(null, false, "10.0.0.2"))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> {
              assertThat(e.getCode()).isEqualTo(ErrorCode.RATE_LIMITED);
              assertThat(e.getDetails().retryAt()).isEqualTo(T0.plus(GuestIssueRateLimiter.WINDOW));
            });
    assertThat(service.issue(null, false, "10.0.0.3"))
        .isInstanceOf(GuestIssueService.Outcome.Issued.class);
  }
}
