package com.lottery.lottery_service.notification;

import com.lottery.lottery_service.common.time.SeoulTime;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 결과 알림 슬롯(D-027, 02 6.2절). 추첨일 21:30~23:30은 30분 간격, 다음 날 09:00~12:00은 30분 간격이고 12:00이 마지막이다. 운영자
 * 알림은 21:30, 23:30, 다음 날 09:00, 12:00 슬롯이 실패할 때만 보낸다.
 */
public final class ResultSlotSchedule {

  private static final List<LocalTime> DRAW_DAY =
      List.of(time(21, 30), time(22, 0), time(22, 30), time(23, 0), time(23, 30));
  private static final List<LocalTime> NEXT_DAY =
      List.of(
          time(9, 0),
          time(9, 30),
          time(10, 0),
          time(10, 30),
          time(11, 0),
          time(11, 30),
          time(12, 0));
  private static final Set<LocalTime> ALERT =
      Set.of(time(21, 30), time(23, 30), time(9, 0), time(12, 0));

  private ResultSlotSchedule() {}

  /** 추첨 시각이 속한 서울 날짜의 슬롯 전체(시각 순). */
  public static List<ResultSlot> slots(Instant drawAt) {
    LocalDate drawDay = SeoulTime.dateOf(drawAt);
    List<ResultSlot> slots = new ArrayList<>();
    for (LocalTime t : DRAW_DAY) {
      slots.add(slot(drawDay, t, false));
    }
    for (LocalTime t : NEXT_DAY) {
      slots.add(slot(drawDay.plusDays(1), t, t.equals(time(12, 0))));
    }
    return slots;
  }

  /** 주어진 시각 이후(포함) 첫 슬롯. 마지막 슬롯이 지났으면 빈 값. */
  public static Optional<ResultSlot> nextAtOrAfter(Instant drawAt, Instant now) {
    return slots(drawAt).stream().filter(s -> !s.at().isBefore(now)).findFirst();
  }

  private static ResultSlot slot(LocalDate day, LocalTime time, boolean last) {
    return new ResultSlot(
        day.atTime(time).atZone(SeoulTime.ZONE).toInstant(), ALERT.contains(time), last);
  }

  private static LocalTime time(int hour, int minute) {
    return LocalTime.of(hour, minute);
  }
}
