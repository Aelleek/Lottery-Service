package com.lottery.lottery_service.notification;

import static com.lottery.lottery_service.common.db.DbValues.instant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lottery.lottery_service.common.error.BusinessException;
import com.lottery.lottery_service.common.error.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 관리자 회차 알림 현황({@code GET /admin/rounds/{round}/notification-status})의 서버 부분(D-056). 슬롯별 결과는 발송 슬롯
 * job 행의 원인 코드와 건수에서 읽는다. 아직 실행하지 않은 슬롯은 넣지 않는다. 권한 확인(OPERATOR 이상)은 관리자 API에서 한다.
 */
@Component
public class RoundNotificationStatusQuery {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final List<String> STATES =
      List.of(
          "PENDING",
          "IN_FLIGHT",
          "ACCEPTED",
          "DELIVERED",
          "RETRY_WAIT",
          "UNKNOWN",
          "HALTED",
          "SUPPRESSED",
          "EXPIRED",
          "FAILED");

  private final JdbcTemplate jdbc;
  private final Clock clock;

  /** 조회기를 만든다. */
  public RoundNotificationStatusQuery(JdbcTemplate jdbc, Clock clock) {
    this.jdbc = jdbc;
    this.clock = clock;
  }

  /**
   * 회차의 알림 현황.
   *
   * @throws BusinessException 회차가 없으면 {@code NOT_FOUND}
   */
  public Status status(int roundNo) {
    List<Map<String, Object>> rounds =
        jdbc.queryForList(
            "SELECT r.published_result_id, d.published_at FROM lotto_round r"
                + " LEFT JOIN draw_result d ON d.id = r.published_result_id WHERE r.round_no = ?",
            roundNo);
    if (rounds.isEmpty()) {
      throw new BusinessException(ErrorCode.NOT_FOUND, "회차를 찾을 수 없습니다.");
    }
    Map<String, Object> round = rounds.get(0);
    String resultState;
    Instant manualUntil = null;
    if (round.get("published_result_id") != null) {
      resultState = "PUBLISHED";
      Instant publishedAt = instant((LocalDateTime) round.get("published_at"));
      manualUntil = publishedAt.plus(NotificationPlanCloser.RESEND_WINDOW);
    } else {
      Long quarantined =
          jdbc.queryForObject(
              "SELECT COUNT(*) FROM draw_result WHERE round_no = ? AND state = 'QUARANTINED'",
              Long.class,
              roundNo);
      resultState = quarantined != null && quarantined > 0 ? "QUARANTINED" : "NOT_PUBLISHED";
    }

    List<Slot> slots = new ArrayList<>();
    boolean lastFinished = false;
    for (Map<String, Object> row :
        jdbc.queryForList(
            "SELECT state, payload, cursor_json, last_error_code FROM job WHERE job_type = ?"
                + " AND JSON_EXTRACT(payload, '$.roundNo') = ? ORDER BY available_at, id",
            ResultSlotJobHandler.JOB,
            roundNo)) {
      String state = (String) row.get("state");
      JsonNode payload = read((String) row.get("payload"));
      boolean finished = "SUCCEEDED".equals(state) || "DEAD".equals(state);
      if (payload.path("last").asBoolean() && finished) {
        lastFinished = true;
      }
      if ("READY".equals(state) || "CANCELED".equals(state)) {
        continue;
      }
      String cause = (String) row.get("last_error_code");
      String outcome =
          !finished
              ? "RUNNING"
              : ("SUCCEEDED".equals(state) && cause == null ? "SUCCEEDED" : "FAILED");
      JsonNode result = read((String) row.get("cursor_json"));
      slots.add(
          new Slot(
              Instant.parse(payload.path("at").asText()),
              "AUTO",
              outcome,
              finished ? cause : null,
              result.path("failed").asInt(0),
              "FAILED".equals(outcome) && payload.path("alert").asBoolean()));
    }

    Map<String, Long> counts = new LinkedHashMap<>();
    STATES.forEach(s -> counts.put(s, 0L));
    jdbc.query(
        "SELECT state, COUNT(*) FROM delivery_message WHERE purpose = 'RESULT' AND round_no = ?"
            + " GROUP BY state",
        rs -> {
          counts.put(rs.getString(1), rs.getLong(2));
        },
        roundNo);
    Long noTarget =
        jdbc.queryForObject(
            "SELECT COUNT(DISTINCT s.member_id) FROM lotto_record r"
                + " JOIN actor_subject s ON s.id = r.owner_subject_id AND s.kind = 'MEMBER'"
                + " WHERE r.round_no = ? AND r.deleted_at IS NULL AND NOT EXISTS ("
                + "SELECT 1 FROM delivery_message m WHERE m.purpose = 'RESULT'"
                + " AND m.round_no = r.round_no AND m.member_id = s.member_id)",
            Long.class,
            roundNo);
    return new Status(
        roundNo,
        resultState,
        lastFinished || (manualUntil != null && !clock.instant().isBefore(manualUntil)),
        manualUntil,
        slots,
        counts,
        noTarget == null ? 0 : noTarget);
  }

  private static JsonNode read(String json) {
    try {
      return json == null ? MAPPER.createObjectNode() : MAPPER.readTree(json);
    } catch (Exception e) {
      throw new IllegalStateException("invalid job json", e);
    }
  }

  /**
   * 회차 알림 현황(openapi {@code RoundNotificationStatus}).
   *
   * @param round 회차
   * @param resultState PUBLISHED, NOT_PUBLISHED, QUARANTINED
   * @param autoDispatchClosed 마지막 자동 슬롯이 끝났으면 {@code true}
   * @param manualDispatchUntil 수동 다시 보내기 기한. 게시 전이면 {@code null}
   * @param slots 실행한 슬롯
   * @param messageCounts 상태별 결과 메시지 수
   * @param noTargetMembers 이 회차에 기록이 있지만 결과 메시지가 없는 회원 수
   */
  public record Status(
      int round,
      String resultState,
      boolean autoDispatchClosed,
      Instant manualDispatchUntil,
      List<Slot> slots,
      Map<String, Long> messageCounts,
      long noTargetMembers) {}

  /**
   * 슬롯 한 번(openapi {@code SlotResult}).
   *
   * @param slotAt 슬롯 시각
   * @param kind AUTO(수동 다시 보내기는 아직 기록하지 않는다)
   * @param outcome RUNNING, SUCCEEDED, FAILED
   * @param causeCode 실패 원인 코드
   * @param failedCount 보내지 못한 수
   * @param operatorAlerted 운영자 알림 슬롯에서 실패했으면 {@code true}
   */
  public record Slot(
      Instant slotAt,
      String kind,
      String outcome,
      String causeCode,
      int failedCount,
      boolean operatorAlerted) {}
}
