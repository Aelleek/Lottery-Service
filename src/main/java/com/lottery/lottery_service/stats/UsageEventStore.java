package com.lottery.lottery_service.stats;

import static com.lottery.lottery_service.common.db.DbValues.bytes;
import static com.lottery.lottery_service.common.db.DbValues.utc;

import com.lottery.lottery_service.common.time.SeoulTime;
import java.time.Instant;
import java.util.List;
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

  /** 구매 등록으로 새로 생기거나 구매 표시가 붙은 조합 수. */
  public static final String PURCHASE_REGISTERED = "PURCHASE_REGISTERED";

  /** 처음 로그인해 회원이 생겼다. 주체 없이 남긴다. */
  public static final String MEMBER_REGISTERED = "MEMBER_REGISTERED";

  /** 생성 권한을 얻은 뒤 저장하지 못하고 끝난 추천 1회. 추천 시도는 성공과 실패의 합이다. */
  public static final String RECOMMENDATION_FAILED = "RECOMMENDATION_FAILED";

  /** 활동(02 9절)으로 세는 사건. 고유 이용자 수는 이 사건만 본다. */
  public static final List<String> ACTIVITY =
      List.of(RECOMMENDATION_SUCCEEDED, PURCHASE_REGISTERED);

  private final JdbcTemplate jdbc;

  /** 저장소를 만든다. */
  public UsageEventStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** 사건 하나를 남긴다. */
  public void record(long subjectId, Integer roundNo, String metric, long delta, Instant at) {
    insert(subjectId, roundNo, metric, delta, at);
  }

  /** 주체 없이 사건을 남긴다. 고유 이용자 수에 들어가지 않는 건수 지표(예: 신규 회원)에 쓴다. */
  public void recordWithoutSubject(String metric, long delta, Instant at) {
    insert(null, null, metric, delta, at);
  }

  private void insert(Long subjectId, Integer roundNo, String metric, long delta, Instant at) {
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
