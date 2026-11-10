package com.lottery.lottery_service.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lottery.lottery_service.account.GuestCredentialStore;
import com.lottery.lottery_service.common.error.BusinessException;
import com.lottery.lottery_service.common.error.ErrorCode;
import com.lottery.lottery_service.common.number.LottoNumbers;
import com.lottery.lottery_service.round.RoundStore;
import com.lottery.lottery_service.round.domain.RoundSchedule;
import com.lottery.lottery_service.support.MutableClock;
import com.lottery.lottery_service.support.MySqlContainerSupport;
import com.lottery.lottery_service.support.TestSecrets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/** 내역 조회의 쪽 나누기와 0건 이유를 MySQL에서 확인한다. */
class RecordHistoryQueryMySqlTest extends MySqlContainerSupport {

  private static final Instant OPEN_1200 = Instant.parse("2026-11-07T21:00:00Z");

  private static DataSource dataSource;

  private final MutableClock clock = new MutableClock(OPEN_1200.plus(Duration.ofDays(1)));
  private JdbcTemplate jdbc;
  private RecordHistoryQuery query;
  private long subject;
  private long other;

  @BeforeAll
  static void migrate() throws Exception {
    dataSource = migratedDatabase("record_history");
  }

  @BeforeEach
  void setUp() {
    jdbc = new JdbcTemplate(dataSource);
    for (String t : List.of("lotto_record", "guest_credential", "actor_subject", "lotto_round")) {
      jdbc.update("DELETE FROM " + t);
    }
    DataSourceTransactionManager tm = new DataSourceTransactionManager(dataSource);
    RoundStore rounds = new RoundStore(jdbc, tm);
    rounds.register(
        new RoundSchedule(
            1199,
            OPEN_1200.minus(Duration.ofDays(7)),
            OPEN_1200.minus(Duration.ofHours(10)),
            OPEN_1200.minus(Duration.ofHours(9)),
            1));
    rounds.register(
        new RoundSchedule(
            1200,
            OPEN_1200,
            OPEN_1200.plus(Duration.ofDays(6)),
            OPEN_1200.plus(Duration.ofDays(6)),
            1));
    GuestCredentialStore guests = new GuestCredentialStore(jdbc, tm, clock, TestSecrets.keys());
    subject = guests.issue().subjectId();
    other = guests.issue().subjectId();
    query = new RecordHistoryQuery(jdbc, rounds, TestSecrets.keys(), clock);
  }

  @Test
  @DisplayName("이번 회차를 10개씩 최신순으로 보여주고, 첫 쪽 뒤에 생긴 기록은 다음 쪽에 끼어들지 않는다")
  void pagesAreStable() {
    for (int i = 0; i < 25; i++) {
      insert(subject, 1200, i, false);
    }
    insert(other, 1200, 0, false);

    RecordPage first = query.list(subject, null, null, null);
    insert(subject, 1200, 30, false);
    RecordPage second = query.list(subject, null, null, first.nextCursor());
    RecordPage third = query.list(subject, null, null, second.nextCursor());

    assertThat(first.selectedRound()).isEqualTo(1200);
    assertThat(first.items()).hasSize(10);
    assertThat(second.items()).hasSize(10);
    assertThat(third.items()).hasSize(5);
    assertThat(third.nextCursor()).isNull();
    List<UUID> all = new ArrayList<>();
    for (RecordPage p : List.of(first, second, third)) {
      p.items().forEach(r -> all.add(r.recordId()));
    }
    assertThat(all).hasSize(25).doesNotHaveDuplicates();
    assertThat(first.emptyReason()).isNull();
  }

  @Test
  @DisplayName("cursor를 바꾸거나 다른 필터·다른 주체에 쓰면 VALIDATION_FAILED")
  void cursorIsBound() {
    for (int i = 0; i < 11; i++) {
      insert(subject, 1200, i, true);
    }
    String cursor = query.list(subject, null, null, null).nextCursor();

    assertInvalid(() -> query.list(subject, null, true, cursor));
    assertInvalid(() -> query.list(subject, 1199, null, cursor));
    assertInvalid(() -> query.list(other, null, null, cursor));
    assertInvalid(() -> query.list(subject, null, null, cursor.substring(1)));
    assertInvalid(() -> query.list(subject, null, null, "x.y"));
  }

  @Test
  @DisplayName("이번 회차 0건이면 NO_CURRENT_RECORDS, 필터 0건은 NO_MATCH, 고른 회차 0건은 NO_RECORDS(T12)")
  void emptyReasons() {
    insert(subject, 1199, 0, false);
    insert(subject, 1200, 1, false);
    jdbc.update("UPDATE lotto_record SET deleted_at = NOW(6) WHERE round_no = 1200");

    assertThat(query.list(subject, null, null, null).emptyReason())
        .isEqualTo(RecordPage.EmptyReason.NO_CURRENT_RECORDS);
    assertThat(query.list(subject, 1199, true, null).emptyReason())
        .isEqualTo(RecordPage.EmptyReason.NO_MATCH);
    assertThat(query.list(other, 1199, null, null).emptyReason())
        .isEqualTo(RecordPage.EmptyReason.NO_RECORDS);
    assertThat(query.list(subject, 1199, null, null).items()).hasSize(1);
  }

  @Test
  @DisplayName("마감 구간의 기본 회차는 방금 마감한 회차다")
  void closedWindowShowsClosedRound() {
    clock.set(OPEN_1200.minus(Duration.ofHours(1)));
    insert(subject, 1199, 0, false);

    RecordPage page = query.list(subject, null, null, null);

    assertThat(page.selectedRound()).isEqualTo(1199);
    assertThat(page.items()).hasSize(1);
  }

  private void insert(long owner, int round, int n, boolean purchased) {
    int x = 7 + n % 39;
    LottoNumbers numbers =
        LottoNumbers.of(1, 2, 3, 4, 5 + n / 39 % 2, x == 5 || x == 6 ? 46 - n : x);
    jdbc.update(
        "INSERT INTO lotto_record (public_id, owner_subject_id, round_no, numbers_key, purchased,"
            + " purchased_at, recommended_at) VALUES (UUID_TO_BIN(UUID()), ?, ?, ?, ?, "
            + (purchased ? "NOW(6)" : "NULL")
            + ", NOW(6))",
        owner,
        round,
        numbers.key(),
        purchased);
  }

  private static void assertInvalid(Runnable call) {
    assertThatThrownBy(call::run)
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.getCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
  }
}
