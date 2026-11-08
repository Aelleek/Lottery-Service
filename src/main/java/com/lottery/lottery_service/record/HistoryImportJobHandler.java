package com.lottery.lottery_service.record;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lottery.lottery_service.common.job.JobHandler;
import com.lottery.lottery_service.common.job.JobLease;
import com.lottery.lottery_service.common.job.JobStep;
import com.lottery.lottery_service.common.job.JobStore;
import java.time.Clock;
import org.springframework.stereotype.Component;

/**
 * 비회원 기록 가져오기 job(05 4절 4번). 사용자가 가져오기를 확인하면 {@link #start}로 등록하고, 묶음마다 마지막 원본 기록 ID를 진행 위치로 남긴다.
 * 로그인 때 거절했어도 나중에 같은 방법으로 다시 시작한다(D-036).
 */
@Component
public class HistoryImportJobHandler implements JobHandler {

  /** 가져오기 job 종류. 중복 키는 귀속 ID다. */
  public static final String JOB = "HISTORY_IMPORT";

  static final int BATCH = 200;

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final HistoryImportStore imports;
  private final JobStore jobs;
  private final Clock clock;

  /** 처리기를 만든다. */
  public HistoryImportJobHandler(HistoryImportStore imports, JobStore jobs, Clock clock) {
    this.imports = imports;
    this.jobs = jobs;
    this.clock = clock;
  }

  /**
   * 가져오기를 시작한다. 같은 귀속의 job은 한 번만 등록된다. 요청한 회원 주체를 함께 남겨 실행 때 귀속의 당사자인지 다시 본다.
   *
   * @return 새로 등록했으면 {@code true}
   */
  public boolean start(long memberSubjectId, long claimId) {
    return jobs.enqueue(
        JOB,
        "claim:" + claimId,
        "{\"claimId\":" + claimId + ",\"memberSubjectId\":" + memberSubjectId + "}",
        clock.instant());
  }

  @Override
  public String type() {
    return JOB;
  }

  @Override
  public JobStep step(JobLease lease, String cursorJson) {
    try {
      JsonNode payload = MAPPER.readTree(lease.payload());
      long claimId = payload.path("claimId").asLong();
      long memberSubjectId = payload.path("memberSubjectId").asLong();
      long after =
          cursorJson == null ? 0 : MAPPER.readTree(cursorJson).path("lastSourceId").asLong();
      ImportBatch batch = imports.importBatch(memberSubjectId, claimId, after, BATCH);
      if (batch.finished()) {
        return new JobStep.Done();
      }
      return new JobStep.Continue("{\"lastSourceId\":" + batch.lastSourceId() + "}");
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("invalid job json", e);
    }
  }
}
