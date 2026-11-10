package com.lottery.lottery_service.common.alert;

import com.lottery.lottery_service.common.job.JobStore;
import com.lottery.lottery_service.common.time.SeoulTime;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/** 인증서·연동 만료 30, 14, 7, 1일 전에 운영자에게 알린다(07 2.1절). 같은 대상·같은 단계는 한 번만 알린다. */
@Component
@EnableConfigurationProperties(ExpiryProperties.class)
public class ExpiryWatch {

  /** 알리는 남은 일수. 그날을 놓쳤으면 다음으로 짧은 단계에서 알린다. */
  public static final List<Integer> THRESHOLDS = List.of(30, 14, 7, 1);

  private final ExpiryProperties properties;
  private final JobStore jobs;
  private final Clock clock;

  /** 감시기를 만든다. */
  public ExpiryWatch(ExpiryProperties properties, JobStore jobs, Clock clock) {
    this.properties = properties;
    this.jobs = jobs;
    this.clock = clock;
  }

  /**
   * 남은 일수가 단계에 들어간 대상마다 알림 job을 등록한다. 호출한 쪽 트랜잭션에서 돈다.
   *
   * @return 이번에 등록한 알림 수
   */
  public int check() {
    LocalDate today = SeoulTime.today(clock);
    int enqueued = 0;
    for (Map.Entry<String, LocalDate> item : new TreeMap<>(properties.items()).entrySet()) {
      long left = ChronoUnit.DAYS.between(today, item.getValue());
      Integer stage = THRESHOLDS.stream().filter(t -> left <= t).reduce((a, b) -> b).orElse(null);
      // 만료일이 지났으면 마지막 단계(1일 전)로 본다. 같은 중복 키라 그 단계를 이미 알렸으면 다시 알리지 않는다.
      if (stage == null) {
        continue;
      }
      boolean added =
          jobs.enqueue(
              JobStore.OPERATOR_ALERT,
              "expiry:" + item.getKey() + ":" + item.getValue() + ":" + stage,
              "{\"kind\":\"EXPIRY\",\"name\":\""
                  + item.getKey().replace("\"", "")
                  + "\",\"expiresOn\":\""
                  + item.getValue()
                  + "\",\"daysLeft\":"
                  + left
                  + "}",
              clock.instant());
      enqueued += added ? 1 : 0;
    }
    return enqueued;
  }
}
