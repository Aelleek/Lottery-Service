package com.lottery.lottery_service.common.alert;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lottery.lottery_service.common.job.JobHandler;
import com.lottery.lottery_service.common.job.JobLease;
import com.lottery.lottery_service.common.job.JobStep;
import com.lottery.lottery_service.common.job.JobStore;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 운영자 알림 job을 보낸다(07 2절). 내용에는 회차, 건수, 원인 코드, 작업 종류만 넣고 전화번호, 번호 조합, 회원 ID, 토큰은 넣지 않는다. 같은 사건은 job
 * 중복 키로 한 번만 등록된다. 전송이 실패하면 job이 다시 시도하고 회원 기능에는 영향이 없다.
 */
@Component
public class OperatorAlertJobHandler implements JobHandler {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final OperatorAlertSender sender;
  private final JdbcTemplate jdbc;

  /** 처리기를 만든다. */
  public OperatorAlertJobHandler(OperatorAlertSender sender, JdbcTemplate jdbc) {
    this.sender = sender;
    this.jdbc = jdbc;
  }

  @Override
  public String type() {
    return JobStore.OPERATOR_ALERT;
  }

  @Override
  public JobStep step(JobLease lease, String cursorJson) {
    sender.send(format(read(lease.payload())));
    return new JobStep.Done();
  }

  String format(JsonNode payload) {
    String kind = payload.path("kind").asText("UNKNOWN");
    return switch (kind) {
      case "JOB_DEAD" -> {
        List<Map<String, Object>> rows =
            jdbc.queryForList(
                "SELECT job_type, last_error_code FROM job WHERE id = ?",
                payload.path("jobId").asLong());
        String type = rows.isEmpty() ? "?" : String.valueOf(rows.get(0).get("job_type"));
        String error = rows.isEmpty() ? "?" : String.valueOf(rows.get(0).get("last_error_code"));
        yield "[작업 실패] 종류 " + type + ", 마지막 오류 " + error + ". 관리자 화면에서 확인해 주세요.";
      }
      case "RESULT_QUARANTINED" -> "[결과 격리] "
          + payload.path("roundNo").asInt()
          + "회 결과가 게시본과 달라 격리했습니다. 승인이 필요합니다.";
      case "MESSAGE_UNRESOLVED" -> {
        List<Integer> rounds =
            jdbc.queryForList(
                "SELECT round_no FROM delivery_message WHERE id = ?",
                Integer.class,
                payload.path("messageId").asLong());
        String round = rounds.isEmpty() || rounds.get(0) == null ? "인증" : rounds.get(0) + "회";
        yield "[접수 확인 종결] " + round + " 메시지 1건을 대조 기간 안에 확정하지 못해 실패로 끝냈습니다.";
      }
      default -> "[운영자 알림] " + kind;
    };
  }

  private static JsonNode read(String json) {
    try {
      return MAPPER.readTree(json);
    } catch (Exception e) {
      throw new IllegalStateException("invalid job json", e);
    }
  }
}
