package com.lottery.lottery_service.result.evaluate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lottery.lottery_service.common.job.JobHandler;
import com.lottery.lottery_service.common.job.JobLease;
import com.lottery.lottery_service.common.job.JobStep;
import com.lottery.lottery_service.record.PurchaseStore;
import org.springframework.stereotype.Component;

/** 기록 하나의 판정 job. 늦은 등록, 가져오기, 기록 변경 뒤에 현재 게시본으로 판정한다(05 7절 4번). */
@Component
public class EvaluateRecordJobHandler implements JobHandler {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final EvaluationStore evaluations;

  /** 처리기를 만든다. */
  public EvaluateRecordJobHandler(EvaluationStore evaluations) {
    this.evaluations = evaluations;
  }

  @Override
  public String type() {
    return PurchaseStore.EVALUATE_RECORD_JOB;
  }

  @Override
  public JobStep step(JobLease lease, String cursorJson) {
    long recordId;
    try {
      recordId = MAPPER.readTree(lease.payload()).path("recordId").asLong();
    } catch (Exception e) {
      throw new IllegalStateException("invalid job json", e);
    }
    evaluations.evaluateRecord(recordId);
    return new JobStep.Done();
  }
}
