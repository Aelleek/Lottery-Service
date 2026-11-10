package com.lottery.lottery_service.admin.web;

import com.lottery.lottery_service.admin.AdminAccess;
import com.lottery.lottery_service.admin.AdminRole;
import com.lottery.lottery_service.auth.MemberPrincipal;
import com.lottery.lottery_service.common.error.BusinessException;
import com.lottery.lottery_service.common.error.ErrorCode;
import com.lottery.lottery_service.notification.RoundNotificationStatusQuery;
import com.lottery.lottery_service.stats.AdminStatsQuery;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관리자 조회 API(06 4절): 회차 알림 현황, 기간별·회차별 기본 지표. 모두 OPERATOR 이상이고 관리자 세션은 앞의 확인기가 본다. 응답에 번호 조합, 회원
 * ID, 전화번호를 넣지 않는다.
 */
@RestController
@RequestMapping("/api/v1")
@ConditionalOnProperty(prefix = "lottery.api-v1", name = "enabled", havingValue = "true")
public class AdminQueryController {

  private final AdminAccess access;
  private final RoundNotificationStatusQuery notifications;
  private final AdminStatsQuery stats;

  /** 컨트롤러를 만든다. */
  public AdminQueryController(
      AdminAccess access, RoundNotificationStatusQuery notifications, AdminStatsQuery stats) {
    this.access = access;
    this.notifications = notifications;
    this.stats = stats;
  }

  /** 회차 알림 현황(openapi {@code RoundNotificationStatus}). */
  @GetMapping("/admin/rounds/{round}/notification-status")
  public ResponseEntity<Map<String, Object>> notificationStatus(
      Authentication auth, @PathVariable("round") int round) {
    requireOperator(auth);
    requireRound(round);
    RoundNotificationStatusQuery.Status status = notifications.status(round);
    Map<String, Object> counts = new LinkedHashMap<>();
    status.messageCounts().forEach((state, n) -> counts.put(camel(state), n));
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("round", status.round());
    body.put("resultState", status.resultState());
    body.put("autoDispatchClosed", status.autoDispatchClosed());
    body.put("manualDispatchUntil", status.manualDispatchUntil());
    body.put("slots", status.slots());
    body.put("messageCounts", counts);
    body.put("noTargetMembers", status.noTargetMembers());
    return noStore(body);
  }

  /** 기간별 기본 지표(openapi {@code DailyStats}). 서울 날짜 기준, 최대 92일. */
  @GetMapping("/admin/stats/daily")
  public ResponseEntity<Map<String, Object>> daily(
      Authentication auth, @RequestParam("from") String from, @RequestParam("to") String to) {
    requireOperator(auth);
    AdminStatsQuery.DailyStats daily;
    try {
      daily = stats.daily(LocalDate.parse(from), LocalDate.parse(to));
    } catch (DateTimeParseException e) {
      throw new BusinessException(ErrorCode.VALIDATION_FAILED, "날짜 형식을 확인해 주세요.");
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("from", daily.from());
    body.put("to", daily.to());
    body.put("days", daily.days());
    body.put("calculationVersion", Long.toString(daily.calculationVersion()));
    body.put("asOf", daily.asOf());
    return noStore(body);
  }

  /** 회차별 기본 지표(openapi {@code RoundStats}). */
  @GetMapping("/admin/stats/rounds/{round}")
  public ResponseEntity<Map<String, Object>> round(
      Authentication auth, @PathVariable("round") int round) {
    requireOperator(auth);
    requireRound(round);
    AdminStatsQuery.RoundStats r = stats.round(round);
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("round", r.round());
    body.put("generatedSets", r.generatedSets());
    body.put("evaluatedRecords", r.evaluatedRecords());
    body.put("pendingRecords", r.pendingRecords());
    body.put("rankCounts", r.rankCounts());
    body.put("notificationsAccepted", r.notificationsAccepted());
    body.put("notificationsFailed", r.notificationsFailed());
    body.put("calculationVersion", Long.toString(r.calculationVersion()));
    body.put("asOf", r.asOf());
    return noStore(body);
  }

  private void requireOperator(Authentication auth) {
    if (auth == null || !(auth.getPrincipal() instanceof MemberPrincipal member)) {
      throw new BusinessException(ErrorCode.UNAUTHENTICATED, "로그인이 필요합니다.");
    }
    access.require(member.memberId(), AdminRole.OPERATOR);
  }

  private static void requireRound(int round) {
    if (round < 1) {
      throw new BusinessException(ErrorCode.VALIDATION_FAILED, "회차를 확인해 주세요.");
    }
  }

  private static String camel(String state) {
    StringBuilder out = new StringBuilder();
    boolean upper = false;
    for (char c : state.toLowerCase(java.util.Locale.ROOT).toCharArray()) {
      if (c == '_') {
        upper = true;
      } else {
        out.append(upper ? Character.toUpperCase(c) : c);
        upper = false;
      }
    }
    return out.toString();
  }

  private static ResponseEntity<Map<String, Object>> noStore(Map<String, Object> body) {
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
  }
}
