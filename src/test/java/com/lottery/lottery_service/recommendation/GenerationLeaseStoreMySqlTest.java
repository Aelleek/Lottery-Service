package com.lottery.lottery_service.recommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lottery.lottery_service.account.GuestCredentialStore;
import com.lottery.lottery_service.common.error.BusinessException;
import com.lottery.lottery_service.common.error.ErrorCode;
import com.lottery.lottery_service.support.MutableClock;
import com.lottery.lottery_service.support.MySqlContainerSupport;
import com.lottery.lottery_service.support.TestSecrets;
import java.time.Instant;
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
import org.springframework.transaction.support.TransactionTemplate;

/** {@link GenerationLeaseStore}가 주체당 생성 하나와 최소 간격을 지키는지 MySQL에서 확인한다. */
class GenerationLeaseStoreMySqlTest extends MySqlContainerSupport {

  private static final Instant T0 = Instant.parse("2026-11-07T01:00:00Z");

  private static DataSource dataSource;

  private final MutableClock clock = new MutableClock(T0);
  private JdbcTemplate jdbc;
  private TransactionTemplate tx;
  private GenerationLeaseStore leases;
  private long subject;

  @BeforeAll
  static void migrate() throws Exception {
    dataSource = migratedDatabase("generation_lease");
  }

  @BeforeEach
  void setUp() {
    jdbc = new JdbcTemplate(dataSource);
    jdbc.update("DELETE FROM guest_credential");
    jdbc.update("DELETE FROM actor_subject");
    DataSourceTransactionManager tm = new DataSourceTransactionManager(dataSource);
    tx = new TransactionTemplate(tm);
    leases = new GenerationLeaseStore(jdbc, tm, clock);
    subject = new GuestCredentialStore(jdbc, tm, clock, TestSecrets.keys()).issue().subjectId();
  }

  @Test
  @DisplayName("생성 중에 다시 오면 GENERATION_BUSY와 권한 만료 시각을 돌려준다")
  void busyWhileGenerating() {
    GenerationLease lease = leases.acquire(subject);

    assertThat(lease.fence()).isEqualTo(1);
    assertBusy(() -> leases.acquire(subject), lease.leaseUntil());
  }

  @Test
  @DisplayName("생성을 끝내면 1초 뒤부터 다시 생성할 수 있다")
  void minimumInterval() {
    GenerationLease lease = leases.acquire(subject);
    clock.set(T0.plusMillis(200));
    assertThat(leases.finish(lease)).isTrue();

    clock.set(T0.plusMillis(1199));
    assertBusy(() -> leases.acquire(subject), T0.plusMillis(1200));
    clock.set(T0.plusMillis(1200));
    assertThat(leases.acquire(subject).fence()).isEqualTo(2);
  }

  @Test
  @DisplayName("권한이 만료되면 새 생성이 시작되고, 늦게 끝난 옛 생성은 저장도 끝내기도 못 한다")
  void staleGenerationRejected() {
    GenerationLease old = leases.acquire(subject);
    clock.set(old.leaseUntil());
    GenerationLease fresh = leases.acquire(subject);

    assertThatThrownBy(() -> tx.executeWithoutResult(s -> leases.requireCurrent(old)))
        .isInstanceOf(GenerationSupersededException.class);
    assertThat(leases.finish(old)).isFalse();
    tx.executeWithoutResult(s -> leases.requireCurrent(fresh));
    assertThat(leases.finish(fresh)).isTrue();
  }

  @Test
  @DisplayName("권한을 잡은 뒤 귀속(귀속 버전 변경)이나 상태 변경이 끼어들면 저장 확인이 거부된다(05 2절 8번)")
  void claimBetweenAcquireAndSaveRejected() {
    GenerationLease lease = leases.acquire(subject);
    jdbc.update("UPDATE actor_subject SET claim_version = claim_version + 1 WHERE id = ?", subject);

    assertThatThrownBy(() -> tx.executeWithoutResult(s -> leases.requireCurrent(lease)))
        .isInstanceOf(GenerationSupersededException.class);

    clock.set(clock.instant().plus(GenerationLeaseStore.LEASE).plusSeconds(1));
    GenerationLease fresh = leases.acquire(subject);
    jdbc.update("UPDATE actor_subject SET state = 'CLAIMED' WHERE id = ?", subject);
    assertThatThrownBy(() -> tx.executeWithoutResult(s -> leases.requireCurrent(fresh)))
        .isInstanceOf(GenerationSupersededException.class);
  }

  @Test
  @DisplayName("같은 주체의 동시 요청은 하나만 생성 권한을 얻는다(T03의 생성 부분)")
  void concurrentAcquire() throws Exception {
    CountDownLatch start = new CountDownLatch(1);
    List<CompletableFuture<Boolean>> calls = new ArrayList<>();
    for (int i = 0; i < 4; i++) {
      calls.add(
          CompletableFuture.supplyAsync(
              () -> {
                try {
                  start.await();
                  leases.acquire(subject);
                  return true;
                } catch (BusinessException e) {
                  return false;
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                  return false;
                }
              }));
    }
    start.countDown();

    long acquired = 0;
    for (CompletableFuture<Boolean> c : calls) {
      acquired += c.get(10, TimeUnit.SECONDS) ? 1 : 0;
    }
    assertThat(acquired).isEqualTo(1);
  }

  @Test
  @DisplayName("귀속된 비회원 주체는 새 추천을 생성할 수 없다")
  void inactiveSubjectRejected() {
    jdbc.update("UPDATE actor_subject SET state = 'CLAIMED' WHERE id = ?", subject);

    assertThatThrownBy(() -> leases.acquire(subject))
        .isInstanceOfSatisfying(
            BusinessException.class, e -> assertThat(e.getCode()).isEqualTo(ErrorCode.FORBIDDEN));
  }

  private static void assertBusy(Runnable call, Instant retryAt) {
    assertThatThrownBy(call::run)
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> {
              assertThat(e.getCode()).isEqualTo(ErrorCode.GENERATION_BUSY);
              assertThat(e.getDetails().retryAt()).isEqualTo(retryAt);
            });
  }
}
