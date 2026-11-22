package com.lottery.lottery_service.common.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class RetryingTransactionTemplateTest {

  private final RetryingTransactionTemplate tx =
      new RetryingTransactionTemplate(mock(PlatformTransactionManager.class));

  @AfterEach
  void reset() {
    TransactionSynchronizationManager.setActualTransactionActive(false);
  }

  @Test
  @DisplayName("교착·잠금 시간 초과는 처음부터 다시 실행하고, 두 번 다시 해도 실패하면 그 예외를 던진다")
  void retriesLockFailuresTwice() {
    AtomicInteger calls = new AtomicInteger();
    String result =
        tx.execute(
            s -> {
              if (calls.incrementAndGet() == 1) {
                throw new PessimisticLockingFailureException("deadlock");
              }
              if (calls.get() == 2) {
                throw new CannotAcquireLockException("timeout");
              }
              return "ok";
            });
    assertThat(result).isEqualTo("ok");
    assertThat(calls.get()).isEqualTo(3);

    calls.set(0);
    assertThatThrownBy(
            () ->
                tx.execute(
                    s -> {
                      calls.incrementAndGet();
                      throw new PessimisticLockingFailureException("deadlock");
                    }))
        .isInstanceOf(PessimisticLockingFailureException.class);
    assertThat(calls.get()).isEqualTo(1 + RetryingTransactionTemplate.MAX_RETRIES);
  }

  @Test
  @DisplayName("잠금 실패가 아닌 예외와 바깥 트랜잭션 안의 잠금 실패는 다시 실행하지 않는다")
  void doesNotRetryOtherFailuresOrNested() {
    AtomicInteger calls = new AtomicInteger();
    assertThatThrownBy(
            () ->
                tx.execute(
                    s -> {
                      calls.incrementAndGet();
                      throw new DataIntegrityViolationException("dup");
                    }))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThat(calls.get()).isEqualTo(1);

    TransactionSynchronizationManager.setActualTransactionActive(true);
    calls.set(0);
    assertThatThrownBy(
            () ->
                tx.execute(
                    s -> {
                      calls.incrementAndGet();
                      throw new PessimisticLockingFailureException("deadlock");
                    }))
        .isInstanceOf(PessimisticLockingFailureException.class);
    assertThat(calls.get()).isEqualTo(1);
  }
}
