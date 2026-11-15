package com.lottery.lottery_service.common.db;

import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 교착이나 잠금 시간 초과로 실패한 트랜잭션을 처음부터 최대 2회 다시 실행한다(04 4절). 이미 바깥 트랜잭션 안이면 다시 실행하지 않고 그대로 던진다. 콜백은 DB 작업만
 * 해야 한다. 외부 호출은 넣지 않는다.
 */
public class RetryingTransactionTemplate extends TransactionTemplate {

  /** 처음 시도 뒤 다시 실행하는 최대 횟수. */
  public static final int MAX_RETRIES = 2;

  /** 템플릿을 만든다. */
  public RetryingTransactionTemplate(PlatformTransactionManager transactionManager) {
    super(transactionManager);
  }

  @Override
  public <T> T execute(TransactionCallback<T> action) throws TransactionException {
    boolean outermost = !TransactionSynchronizationManager.isActualTransactionActive();
    for (int attempt = 0; ; attempt++) {
      try {
        return super.execute(action);
      } catch (PessimisticLockingFailureException e) {
        if (!outermost || attempt >= MAX_RETRIES) {
          throw e;
        }
      }
    }
  }
}
