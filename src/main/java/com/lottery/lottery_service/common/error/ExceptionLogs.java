package com.lottery.lottery_service.common.error;

import java.sql.SQLException;
import java.util.Arrays;
import org.slf4j.Logger;
import org.springframework.dao.DataAccessException;

/**
 * 예외를 로그에 남기는 공통 규칙(03 6.1절). DB 예외는 메시지와 원인 체인에 SQL 값(번호 조합, 전화번호 암호문 등)이 실릴 수 있어 예외 종류와 SQL 상태
 * 코드만 남긴다. 그 밖의 예외는 원인과 함께 남긴다.
 */
public final class ExceptionLogs {

  private ExceptionLogs() {}

  /**
   * 경고 로그를 남긴다. {@code format}의 자리 표시자는 {@code args}로 채우고, 끝에 예외 요약(DB 예외) 또는 예외 자체를 붙인다.
   *
   * @param log 남길 로거
   * @param format slf4j 형식 문자열
   * @param e 남길 예외
   * @param args 형식 인자
   */
  public static void warn(Logger log, String format, Throwable e, Object... args) {
    SQLException sql = sqlCause(e);
    if (e instanceof DataAccessException || sql != null) {
      Object[] all = Arrays.copyOf(args, args.length + 3);
      all[args.length] = e.getClass().getSimpleName();
      all[args.length + 1] = sql == null ? null : sql.getSQLState();
      all[args.length + 2] = sql == null ? null : sql.getErrorCode();
      log.warn(format + " dbError={} sqlState={} errorCode={}", all);
      return;
    }
    Object[] all = Arrays.copyOf(args, args.length + 1);
    all[args.length] = e;
    log.warn(format, all);
  }

  /** 원인 체인에서 처음 나오는 {@link SQLException}. 없으면 {@code null}. */
  public static SQLException sqlCause(Throwable e) {
    Throwable current = e;
    for (int depth = 0; current != null && depth < 16; depth++) {
      if (current instanceof SQLException sql) {
        return sql;
      }
      current = current.getCause();
    }
    return null;
  }
}
