package com.lottery.lottery_service.notification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lottery.lottery_service.common.job.JobHandler;
import com.lottery.lottery_service.common.job.JobLease;
import com.lottery.lottery_service.common.job.JobStep;
import com.lottery.lottery_service.result.source.ResultCollector;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 발송 슬롯 job(05 8.2절). 중복 키는 (회차, 슬롯 시각)이다. 결과가 아직 없으면 수집을 한 번 시도한 뒤 슬롯을 실행한다. 외부 호출이 있어 실행기의 트랜잭션
 * 밖에서 돈다.
 */
@Component
public class ResultSlotJobHandler implements JobHandler {

  /** 발송 슬롯 job 종류. */
  public static final String JOB = "RESULT_SLOT";

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final ResultSlotRunner runner;
  private final ResultCollector collector;
  private final JdbcTemplate jdbc;

  /** 처리기를 만든다. */
  public ResultSlotJobHandler(
      ResultSlotRunner runner, ResultCollector collector, JdbcTemplate jdbc) {
    this.runner = runner;
    this.collector = collector;
    this.jdbc = jdbc;
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
    ResultSlot slot =
        new ResultSlot(
            Instant.parse(payload.path("at").asText()),
            payload.path("alert").asBoolean(),
            payload.path("last").asBoolean());
    Long published =
        jdbc.queryForObject(
            "SELECT published_result_id FROM lotto_round WHERE round_no = ?", Long.class, roundNo);
    if (published == null) {
      collector.collect(roundNo);
    }
    runner.run(roundNo, slot);
    return new JobStep.Done();
  }

  private static JsonNode read(String json) {
    try {
      return MAPPER.readTree(json);
    } catch (Exception e) {
      throw new IllegalStateException("invalid job json", e);
    }
  }
}
