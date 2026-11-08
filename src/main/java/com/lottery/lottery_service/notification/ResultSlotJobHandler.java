package com.lottery.lottery_service.notification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lottery.lottery_service.common.db.DbValues;
import com.lottery.lottery_service.common.job.JobHandler;
import com.lottery.lottery_service.common.job.JobLease;
import com.lottery.lottery_service.common.job.JobStep;
import com.lottery.lottery_service.result.source.ResultCollector;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 발송 슬롯 job(05 8.2절). 중복 키는 (회차, 슬롯 시각)이다. 결과가 아직 없으면 수집을 한 번 시도한 뒤 슬롯을 실행한다. 외부 호출이 있어 실행기의 트랜잭션
 * 밖에서 돈다. 슬롯 결과(원인 코드, 못 보낸 수, 확인 중인 수)는 그 job 행의 {@code last_error_code}와 {@code cursor_json}에 남겨
 * 관리자 회차 알림 현황이 읽는다(D-056).
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
    SlotResult result = runner.run(roundNo, slot);
    jdbc.update(
        "UPDATE job SET last_error_code = ?, cursor_json = ? WHERE id = ? AND lease_token = ?",
        result.cause(),
        "{\"success\":"
            + result.success()
            + ",\"failed\":"
            + result.failed()
            + ",\"unknown\":"
            + result.unknown()
            + "}",
        lease.id(),
        DbValues.bytes(lease.token()));
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
