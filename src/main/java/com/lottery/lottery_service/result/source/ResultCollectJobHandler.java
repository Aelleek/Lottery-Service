package com.lottery.lottery_service.result.source;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lottery.lottery_service.common.job.JobHandler;
import com.lottery.lottery_service.common.job.JobLease;
import com.lottery.lottery_service.common.job.JobStep;
import com.lottery.lottery_service.common.job.JobStore;
import java.time.Clock;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 결과 수집 job(07 1절). 한 번 수집하고, 아직 게시되지 않았거나 직접 등록한 게시본을 결과 API가 아직 확인(같으면 확인 표시, 다르면 격리)하지 않았으면
 * {@link CollectionSchedule}의 다음 시각에 다음 시도를 등록한다(05 6절 직접 등록 5번). 시도마다 job이 하나라 중복 키에 시각을 넣는다. 게시되지
 * 않았으면 그 시도의 결과(미발표, 수집 실패)를 job의 마지막 오류 코드로 남겨 결과 조회가 미발표와 수집 지연을 나눈다. 외부 호출이 있어 실행기의 트랜잭션 밖에서 돈다.
 */
@Component
public class ResultCollectJobHandler implements JobHandler {

  /** 결과 수집 job 종류. */
  public static final String JOB = "RESULT_COLLECT";

  /** 시도 결과가 미발표였을 때 job에 남기는 코드(07 2.2절). */
  public static final String NOT_ANNOUNCED = "RESULT_NOT_PUBLISHED";

  /** 시도가 결과 API 호출이나 응답 검증에서 실패했을 때 job에 남기는 코드(07 2.2절). */
  public static final String FETCH_ERROR = "RESULT_FETCH_ERROR";

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final ResultCollector collector;
  private final JobStore jobs;
  private final JdbcTemplate jdbc;
  private final TransactionTemplate tx;
  private final Clock clock;

  /** 처리기를 만든다. */
  public ResultCollectJobHandler(
      ResultCollector collector,
      JobStore jobs,
      JdbcTemplate jdbc,
      PlatformTransactionManager transactionManager,
      Clock clock) {
    this.collector = collector;
    this.jobs = jobs;
    this.jdbc = jdbc;
    this.tx = new TransactionTemplate(transactionManager);
    this.clock = clock;
  }

  /** 회차의 첫 수집 시도를 등록한다(추첨 10분 뒤). */
  public boolean plan(int roundNo, Instant drawAt) {
    return schedule(roundNo, drawAt, CollectionSchedule.nextAttempt(drawAt, null));
  }

  @Override
  public String type() {
    return JOB;
  }

  @Override
  public boolean transactional() {
    return false;
  }

  @Override
  public JobStep step(JobLease lease, String cursorJson) {
    JsonNode payload = read(lease.payload());
    int roundNo = payload.path("roundNo").asInt();
    Instant drawAt = Instant.parse(payload.path("drawAt").asText());
    ParsedDraw parsed = null;
    if (needsCollection(roundNo)) {
      parsed = collector.collect(roundNo).parsed();
    }
    if (needsCollection(roundNo)) {
      Instant next = CollectionSchedule.nextAttempt(drawAt, clock.instant());
      String code = parsed instanceof ParsedDraw.NotAnnounced ? NOT_ANNOUNCED : FETCH_ERROR;
      tx.executeWithoutResult(
          status -> {
            jdbc.update("UPDATE job SET last_error_code = ? WHERE id = ?", code, lease.id());
            schedule(roundNo, drawAt, next);
          });
    }
    return new JobStep.Done();
  }

  private boolean schedule(int roundNo, Instant drawAt, Instant at) {
    return jobs.enqueue(
        JOB,
        dedupePrefix(roundNo) + at,
        "{\"roundNo\":" + roundNo + ",\"drawAt\":\"" + drawAt + "\"}",
        at);
  }

  /** 회차의 수집 job 중복 키 앞부분. 뒤에 시도 시각이 붙는다. */
  public static String dedupePrefix(int roundNo) {
    return "collect:" + roundNo + ":";
  }

  private boolean needsCollection(int roundNo) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT r.published_result_id IS NULL OR (d.source = 'MANUAL'"
                + " AND d.api_confirmed_at IS NULL AND NOT EXISTS (SELECT 1 FROM draw_result q"
                + " WHERE q.round_no = r.round_no AND q.state = 'QUARANTINED'))"
                + " FROM lotto_round r LEFT JOIN draw_result d ON d.id = r.published_result_id"
                + " WHERE r.round_no = ?",
            Boolean.class,
            roundNo));
  }

  private static JsonNode read(String json) {
    try {
      return MAPPER.readTree(json);
    } catch (Exception e) {
      throw new IllegalStateException("invalid job json", e);
    }
  }
}
