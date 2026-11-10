package com.lottery.lottery_service.common.alert;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lottery.lottery_service.common.job.JobHandler;
import com.lottery.lottery_service.common.job.JobLease;
import com.lottery.lottery_service.common.job.JobStep;
import com.lottery.lottery_service.common.job.JobStore;
import com.lottery.lottery_service.common.time.SeoulTime;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
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
  private static final DateTimeFormatter SLOT_TIME = DateTimeFormatter.ofPattern("M월 d일 HH:mm");

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
      case "RESULT_CORRECTED" -> "[공식 결과 정정 반영] "
          + payload.path("roundNo").asInt()
          + "회 정정 게시본의 판정을 마쳤습니다. 등수나 금액이 바뀐 회원 "
          + payload.path("changed").asLong()
          + "명, 새로 대상이 된 회원 "
          + payload.path("newlyTargeted").asLong()
          + "명. 정정 알림은 보내지 않습니다. 새로 대상이 된 회원은 자동 슬롯 기간이 지났으면 다시 보내기로 보낼 수 있습니다.";
      case "RESULT_QUARANTINED" -> "[결과 격리] "
          + payload.path("roundNo").asInt()
          + "회 결과가 게시본과 달라 격리했습니다."
          + changedNames(payload.path("changed"))
          + " 승인이 필요합니다.";
      case "MESSAGE_UNRESOLVED" -> {
        List<Integer> rounds =
            jdbc.queryForList(
                "SELECT round_no FROM delivery_message WHERE id = ?",
                Integer.class,
                payload.path("messageId").asLong());
        String round = rounds.isEmpty() || rounds.get(0) == null ? "인증" : rounds.get(0) + "회";
        yield "[접수 확인 종결] " + round + " 메시지 1건을 대조 기간 안에 확정하지 못해 실패로 끝냈습니다.";
      }
      case "SLOT_FAILED" -> "[결과 알림 슬롯 실패] "
          + payload.path("roundNo").asInt()
          + "회"
          + (payload.path("slotAt").isTextual()
              ? " "
                  + SLOT_TIME.format(
                      SeoulTime.atSeoul(Instant.parse(payload.path("slotAt").asText())))
                  + " 슬롯"
              : "")
          + ", 원인 "
          + payload.path("cause").asText()
          + (payload.path("targets").isNumber()
              ? ", 대상 " + payload.path("targets").asLong() + "명"
              : payload.has("targets") ? ", 대상 수는 판정 뒤 정해짐" : "")
          + ", 미발송 "
          + payload.path("failed").asInt()
          + "건"
          + (payload.has("budgetBlocked")
              ? "(예산 차단 "
                  + payload.path("budgetBlocked").asInt()
                  + "건, 제공자 미접수·거절 "
                  + payload.path("providerFailed").asInt()
                  + "건)"
              : "")
          + ", 접수 확인 중 "
          + payload.path("unknown").asInt()
          + "건."
          + (payload.path("last").asBoolean() ? " 자동 발송을 끝냈습니다. 관리자 화면의 다시 보내기로 보낼 수 있습니다." : "");
      case "BUDGET_MISMATCH" -> "[예산 카운터 불일치] "
          + payload.path("month").asText()
          + ", 카운터 예약 "
          + won(payload.path("counterReserved").asLong())
          + "·사용 "
          + won(payload.path("counterSpent").asLong())
          + ", 메시지 합계 예약 "
          + won(payload.path("messageReserved").asLong())
          + "·사용 "
          + won(payload.path("messageSpent").asLong())
          + ".";
      case "BUDGET_THRESHOLD" -> "[메시지 예산] "
          + payload.path("month").asText()
          + " 사용 "
          + won(payload.path("used").asLong())
          + ", 남은 "
          + won(Math.max(0, payload.path("limit").asLong() - payload.path("used").asLong()))
          + ". 월 예산의 80%에 닿았습니다.";
      case "RESULT_MANUAL_REGISTERED" -> "[결과 직접 등록] "
          + payload.path("roundNo").asInt()
          + "회 결과를 ADMIN이 직접 등록해 게시했습니다.";
      case "MESSAGE_UNKNOWN_LONG" -> "[접수 확인 장기화] 접수 여부를 모르는 메시지 "
          + payload.path("count").asLong()
          + "건이 24시간 넘게 풀리지 않았습니다. 가장 오래된 발송 시도 "
          + payload.path("oldest").asText()
          + ".";
      case "MANUAL_RESEND" -> "[수동 다시 보내기] "
          + payload.path("roundNo").asInt()
          + "회, 실행 "
          + payload.path("actor").asText()
          + ", 접수 "
          + payload.path("sent").asLong()
          + "건, 실패 "
          + payload.path("failed").asLong()
          + "건, 접수 확인 중 "
          + payload.path("unknown").asLong()
          + "건.";
      case "DELETION_JOURNAL_FAILED" -> "[삭제 기록 저장 실패] 삭제 요청 "
          + payload.path("requestId").asText()
          + ". 탈퇴 회원의 접근은 막혀 있고 개인 자료 삭제는 삭제 기록을 남길 때까지 멈춰 있습니다.";
      case "EXPIRY" -> "[인증서·연동 만료] "
          + payload.path("name").asText()
          + " 만료일 "
          + payload.path("expiresOn").asText()
          + (payload.path("daysLeft").asLong() < 0
              ? "(이미 만료됨)."
              : "(남은 " + payload.path("daysLeft").asLong() + "일).");
      case "CLEANUP_DELAYED" -> "[정리 지연] "
          + payload.path("roundNo").asInt()
          + "회 기록 "
          + payload.path("count").asLong()
          + "건을 보관 기간이 지나고 7일 넘게 지우지 못했습니다. 이유 "
          + payload.path("reason").asText()
          + ".";
      case "ADMIN_MFA_LOCKED" -> "[관리자 인증 잠금] 인증 앱 코드를 5번 틀려 "
          + payload.path("lockMinutes").asLong()
          + "분 잠갔습니다. 본인이 아니면 그 관리자 권한을 회수하고 감사 기록을 확인하세요.";
      case "ADMIN_CHANGE" -> "[관리자 권한 변경] "
          + adminAction(payload.path("action").asText())
          + (payload.path("role").isTextual() ? "(역할 " + payload.path("role").asText() + ")" : "")
          + ". 본인이 한 변경이 아니면 감사 기록을 확인하세요.";
      case "SECURITY_SIGNAL" -> "[보안 신호] 10분 동안 "
          + signalName(payload.path("signal").asText())
          + " "
          + payload.path("count").asLong()
          + "건. 많이 걸린 경로 `"
          + payload.path("topPath").asText()
          + "`.";
      default -> "[운영자 알림] " + kind;
    };
  }

  private static String changedNames(JsonNode changed) {
    List<String> names = new java.util.ArrayList<>();
    changed.forEach(
        field -> {
          switch (field.asText()) {
            case "NUMBERS" -> names.add("당첨 번호");
            case "BONUS" -> names.add("보너스 번호");
            default -> names.add("기타");
          }
        });
    return names.isEmpty() ? "" : " 바뀐 항목: " + String.join(", ", names) + ".";
  }

  private static String signalName(String signal) {
    return switch (signal) {
      case "TOO_MANY_REQUESTS" -> "요청 제한(429)";
      case "CSRF_REJECTED" -> "CSRF 거절";
      case "ORIGIN_REJECTED" -> "출처 검사 거절";
      default -> "알 수 없는 신호";
    };
  }

  private static String adminAction(String action) {
    return switch (action) {
      case "ADMIN_GRANT_BY_COMMAND" -> "서버 명령으로 권한 부여";
      case "ADMIN_GRANT" -> "권한 부여·역할 변경";
      case "ADMIN_REVOKE" -> "권한 회수";
      case "ADMIN_MFA_RESET" -> "2차 인증 초기화";
      default -> "알 수 없는 변경";
    };
  }

  private static String won(long units) {
    return units / 10 + "." + units % 10 + "원";
  }

  private static JsonNode read(String json) {
    try {
      return MAPPER.readTree(json);
    } catch (Exception e) {
      throw new IllegalStateException("invalid job json", e);
    }
  }
}
