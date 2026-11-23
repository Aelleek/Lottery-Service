package com.lottery.lottery_service.admin;

import static com.lottery.lottery_service.common.db.DbValues.utc;

import com.lottery.lottery_service.common.time.SeoulTime;
import com.lottery.lottery_service.common.web.SecuritySignals;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 관리자 역할이 하나도 없는 회원의 관리자 API 요청을 남긴다(D-062). 요청마다 보안 신호 "관리자 API 거부"로 세고, 감사에는 사유 없는 거부 행을 회원마다 서울
 * 날짜로 하루 1건만 남긴다. 남기지 못해도 요청은 그대로 403으로 답한다.
 */
@Slf4j
@Component
public class AdminAccessDenials {

  /** 하루 안에 이미 남긴 회원을 기억하는 수 상한. 넘으면 DB 확인만으로 거른다. DB 확인과 쓰기는 회원별 잠금 안에서 한다. */
  static final int MAX_REMEMBERED = 100_000;

  private final SecuritySignals signals;
  private final AdminAuditLog audit;
  private final JdbcTemplate jdbc;
  private final Clock clock;
  private final int maxRemembered;
  private final Map<Long, LocalDate> recorded = new ConcurrentHashMap<>();
  private final Object[] locks = new Object[64];

  /** 기록기를 만든다. */
  @Autowired
  public AdminAccessDenials(
      SecuritySignals signals, AdminAuditLog audit, JdbcTemplate jdbc, Clock clock) {
    this(signals, audit, jdbc, clock, MAX_REMEMBERED);
  }

  AdminAccessDenials(
      SecuritySignals signals,
      AdminAuditLog audit,
      JdbcTemplate jdbc,
      Clock clock,
      int maxRemembered) {
    this.signals = signals;
    this.audit = audit;
    this.jdbc = jdbc;
    this.clock = clock;
    this.maxRemembered = maxRemembered;
    for (int i = 0; i < locks.length; i++) {
      locks[i] = new Object();
    }
  }

  /** 거부 한 건을 센다. 그 회원의 오늘 첫 거부면 감사에도 남긴다. */
  public void deny(long memberId, String path) {
    signals.record(SecuritySignals.Kind.ADMIN_API_DENIED, path);
    Instant now = clock.instant();
    LocalDate today = SeoulTime.dateOf(now);
    if (today.equals(recorded.get(memberId))) {
      return;
    }
    if (recorded.size() >= maxRemembered) {
      recorded.values().removeIf(day -> !today.equals(day));
    }
    boolean remember = recorded.containsKey(memberId) || recorded.size() < maxRemembered;
    if (remember && today.equals(recorded.put(memberId, today))) {
      return;
    }
    try {
      synchronized (locks[Math.floorMod(Long.hashCode(memberId), locks.length)]) {
        writeOncePerDay(memberId, path, today);
      }
    } catch (RuntimeException e) {
      if (remember) {
        recorded.remove(memberId, today);
      }
      log.warn("admin access denial audit failed cause={}", e.getClass().getSimpleName());
    }
  }

  private void writeOncePerDay(long memberId, String path, LocalDate today) {
    Integer already =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM admin_audit_event WHERE actor_member_id = ? AND action = ?"
                + " AND occurred_at >= ?",
            Integer.class,
            memberId,
            AdminAuditEvent.ACCESS_DENIED,
            utc(SeoulTime.startOfDay(today)));
    if (already != null && already > 0) {
      return;
    }
    String route = SecuritySignals.route(path);
    if (route.length() > 80) {
      route = SecuritySignals.OTHER_PATH;
    }
    audit.recordIndependently(AdminAuditEvent.accessDenied(memberId, route));
  }
}
