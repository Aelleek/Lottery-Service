package com.lottery.lottery_service.stats;

import static com.lottery.lottery_service.common.db.DbValues.bytes;
import static com.lottery.lottery_service.common.db.DbValues.utc;

import com.lottery.lottery_service.common.time.SeoulTime;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 활동 사건을 {@code usage_event}에 남긴다. 활동과 같은 트랜잭션에서 부른다(05 9절 1번). 날짜는 서울 기준이다. */
@Component
public class UsageEventStore {

  /** 추천 성공 1회. */
  public static final String RECOMMENDATION_SUCCEEDED = "RECOMMENDATION_SUCCEEDED";

  /** 추천으로 생성한 세트 수. */
  public static final String RECOMMENDATION_SETS = "RECOMMENDATION_SETS";

  private final JdbcTemplate jdbc;

  /** 저장소를 만든다. */
  public UsageEventStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** 사건 하나를 남긴다. */
  public void record(long subjectId, Integer roundNo, String metric, long delta, Instant at) {
    jdbc.update(
        "INSERT INTO usage_event (event_id, subject_id, round_no, business_date, metric, delta,"
            + " occurred_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
        bytes(UUID.randomUUID()),
        subjectId,
        roundNo,
        SeoulTime.dateOf(at),
        metric,
        delta,
        utc(at));
  }
}
