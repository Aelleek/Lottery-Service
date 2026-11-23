package com.lottery.lottery_service.notification;

import com.lottery.lottery_service.common.db.DbValues;
import com.lottery.lottery_service.common.job.JobHandler;
import com.lottery.lottery_service.common.job.JobLease;
import com.lottery.lottery_service.common.job.JobStep;
import com.lottery.lottery_service.common.job.JobStore;
import com.lottery.lottery_service.result.source.ResultCollector;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 발송 슬롯 job(05 8.2절). 중복 키는 (회차, 슬롯 시각)이다. 결과가 아직 없으면 수집을 한 번 시도한 뒤 슬롯을 실행한다. 외부 호출이 있어 실행기의 트랜잭션
 * 밖에서 돈다. 다음 날 12:00 슬롯 뒤 10분이 지나면 보내지 않고 남은 메시지를 멈춘다(수동 다시 보내기 대상). 슬롯 결과(원인 코드, 못 보낸 수, 확인 중인 수)는
 * 그 job 행의 {@code last_error_code}와 {@code cursor_json}에 남겨 관리자 회차 알림 현황이 읽는다(D-056).
 */
@Slf4j
@Component
public class ResultSlotJobHandler implements JobHandler {

  /** 발송 슬롯 job 종류. */
  public static final String JOB = "RESULT_SLOT";

  /** 자동 발송 시간이 지나 남은 메시지를 멈췄을 때의 운영자 알림 종류. */
  public static final String AUTOMATIC_ENDED_ALERT = "AUTOMATIC_SEND_ENDED";

  /** 마지막 슬롯 시각 뒤 자동 발송을 허용하는 시간. */
  static final Duration LAST_SLOT_GRACE = Duration.ofMinutes(10);

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final ResultSlotRunner runner;
  private final ResultCollector collector;
  private final JdbcTemplate jdbc;
  private final JobStore jobs;
  private final ResultMessageStore messages;
  private final Clock clock;

  /** 처리기를 만든다. */
  public ResultSlotJobHandler(
      ResultSlotRunner runner,
      ResultCollector collector,
      JdbcTemplate jdbc,
      JobStore jobs,
      ResultMessageStore messages,
      Clock clock) {
    this.runner = runner;
    this.jobs = jobs;
    this.collector = collector;
    this.jdbc = jdbc;
    this.messages = messages;
    this.clock = clock;
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
            Instant.parse(payload.path("at").asString()),
            payload.path("alert").asBoolean(),
            payload.path("last").asBoolean());
    Instant drawAt =
        DbValues.instant(
            jdbc.queryForObject(
                "SELECT draw_at FROM lotto_round WHERE round_no = ?",
                LocalDateTime.class,
                roundNo));
    Instant deadline = automaticDeadline(drawAt);
    if (!clock.instant().isBefore(deadline)) {
      return endAutomatic(lease, roundNo, slot);
    }
    Long published =
        jdbc.queryForObject(
            "SELECT published_result_id FROM lotto_round WHERE round_no = ?", Long.class, roundNo);
    if (published == null) {
      collector.collect(roundNo);
    }
    jobs.renewLease(lease);
    SlotResult result;
    try {
      result =
          runner.run(
              roundNo,
              slot,
              () -> {
                if (!clock.instant().isBefore(deadline)) {
                  throw new AutomaticWindowClosedException();
                }
                jobs.renewLease(lease);
              });
    } catch (AutomaticWindowClosedException e) {
      return endAutomatic(lease, roundNo, slot);
    }
    jobs.renewLease(lease);
    jobs.saveResult(
        lease,
        result.cause(),
        "{\"success\":"
            + result.success()
            + ",\"failed\":"
            + result.failed()
            + ",\"unknown\":"
            + result.unknown()
            + "}");
    return new JobStep.Done();
  }

  /**
   * 자동 발송을 끝내는 시각. 마지막 슬롯(다음 날 12:00) 뒤로는 자동으로 보내지 않는다(02 6.2절). 마지막 슬롯이 늦게 시작해도 끝낼 수 있게 슬롯 목표
   * 시간(10분)만큼 둔다.
   */
  static Instant automaticDeadline(Instant drawAt) {
    List<ResultSlot> slots = ResultSlotSchedule.slots(drawAt);
    return slots.get(slots.size() - 1).at().plus(LAST_SLOT_GRACE);
  }

  private JobStep endAutomatic(JobLease lease, int roundNo, ResultSlot slot) {
    int newlyHalted = messages.haltRemaining(roundNo);
    long halted = count(roundNo, "HALTED");
    long unknown = count(roundNo, "UNKNOWN");
    log.warn(
        "result slot skipped after automatic window round={} slot={} halted={} newlyHalted={}",
        roundNo,
        slot.at(),
        halted,
        newlyHalted);
    boolean alerted = halted > 0 || unknown > 0;
    if (alerted) {
      jobs.enqueue(
          JobStore.OPERATOR_ALERT,
          "automatic-ended:" + roundNo,
          "{\"kind\":\""
              + AUTOMATIC_ENDED_ALERT
              + "\",\"roundNo\":"
              + roundNo
              + ",\"halted\":"
              + halted
              + ",\"unknown\":"
              + unknown
              + "}",
          clock.instant());
    }
    jobs.saveResult(
        lease,
        null,
        "{\"success\":false,\"failed\":"
            + halted
            + ",\"unknown\":"
            + unknown
            + ",\"skipped\":true,\"alerted\":"
            + alerted
            + "}");
    return new JobStep.Done();
  }

  private long count(int roundNo, String state) {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM delivery_message WHERE round_no = ? AND purpose = 'RESULT'"
            + " AND state = ?",
        Long.class,
        roundNo,
        state);
  }

  private static final class AutomaticWindowClosedException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    AutomaticWindowClosedException() {
      super(null, null, false, false);
    }
  }

  private static JsonNode read(String json) {
    try {
      return MAPPER.readTree(json);
    } catch (Exception e) {
      throw new IllegalStateException("invalid job json", e);
    }
  }
}
