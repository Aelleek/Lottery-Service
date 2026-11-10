package com.lottery.lottery_service.notification;

import com.lottery.lottery_service.common.job.JobStore;
import com.lottery.lottery_service.round.domain.RoundSchedule;
import org.springframework.stereotype.Component;

/**
 * 회차 일정으로 결과 알림 슬롯 job을 미리 등록한다. 슬롯마다 (회차, 슬롯 시각)이 중복 키라 여러 번 불러도 한 번만 등록된다. job의 가능 시각이 슬롯 시각이라 그때
 * 실행된다.
 */
@Component
public class RoundJobPlanner {

  private final JobStore jobs;

  /** 계획기를 만든다. */
  public RoundJobPlanner(JobStore jobs) {
    this.jobs = jobs;
  }

  /**
   * 한 회차의 슬롯 job을 등록한다. 호출한 쪽 트랜잭션이 있으면 거기에 참여한다.
   *
   * @return 새로 등록한 job 수
   */
  public int planSlots(RoundSchedule schedule) {
    int created = 0;
    for (ResultSlot slot : ResultSlotSchedule.slots(schedule.drawAt())) {
      boolean added =
          jobs.enqueue(
              ResultSlotJobHandler.JOB,
              "slot:" + schedule.roundNo() + ":" + slot.at(),
              "{\"roundNo\":"
                  + schedule.roundNo()
                  + ",\"at\":\""
                  + slot.at()
                  + "\",\"alert\":"
                  + slot.alertOnFailure()
                  + ",\"last\":"
                  + slot.last()
                  + "}",
              slot.at());
      if (added) {
        created++;
      }
    }
    return created;
  }
}
