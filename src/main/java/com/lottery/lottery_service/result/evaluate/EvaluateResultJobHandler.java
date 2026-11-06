package com.lottery.lottery_service.result.evaluate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lottery.lottery_service.common.job.JobHandler;
import com.lottery.lottery_service.common.job.JobLease;
import com.lottery.lottery_service.common.job.JobStep;
import com.lottery.lottery_service.result.publish.ResultPublisher;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** 게시본 판정 job. 기록을 묶음 단위로 판정하고 마지막 기록 ID를 진행 위치로 남긴다(05 7절 1번). */
@Component
public class EvaluateResultJobHandler implements JobHandler {

  /** 한 걸음의 기록 수. */
  static final int BATCH = 500;

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final EvaluationStore evaluations;
  private final List<ResultEvaluatedListener> listeners;
  private final int batchSize;

  /** 처리기를 만든다. */
  @Autowired
  public EvaluateResultJobHandler(
      EvaluationStore evaluations, List<ResultEvaluatedListener> listeners) {
    this(evaluations, listeners, BATCH);
  }

  EvaluateResultJobHandler(
      EvaluationStore evaluations, List<ResultEvaluatedListener> listeners, int batch) {
    this.evaluations = evaluations;
    this.listeners = List.copyOf(listeners);
    this.batchSize = batch;
  }

  @Override
  public String type() {
    return ResultPublisher.EVALUATE_RESULT_JOB;
  }

  @Override
  public JobStep step(JobLease lease, String cursorJson) {
    long resultId = read(lease.payload()).path("resultId").asLong();
    long after = cursorJson == null ? 0 : read(cursorJson).path("lastRecordId").asLong();
    EvaluationBatch batch = evaluations.evaluateBatch(resultId, after, batchSize);
    if (batch.finished()) {
      if (!batch.superseded()) {
        listeners.forEach(l -> l.evaluated(resultId));
      }
      return new JobStep.Done();
    }
    return new JobStep.Continue("{\"lastRecordId\":" + batch.lastRecordId() + "}");
  }

  private static JsonNode read(String json) {
    try {
      return MAPPER.readTree(json);
    } catch (Exception e) {
      throw new IllegalStateException("invalid job json", e);
    }
  }
}
