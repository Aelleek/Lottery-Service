package com.lottery.lottery_service.result.publish;

import static com.lottery.lottery_service.common.db.DbValues.bytes;

import com.lottery.lottery_service.common.error.BusinessException;
import com.lottery.lottery_service.common.error.ErrorCode;
import com.lottery.lottery_service.common.error.ErrorDetails;
import com.lottery.lottery_service.common.job.JobStore;
import com.lottery.lottery_service.result.domain.WinningNumbers;
import com.lottery.lottery_service.result.source.OfficialDraw;
import com.lottery.lottery_service.result.source.RankPrize;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 결과를 게시한다(05 6절). 게시 트랜잭션은 회차 행만 배타 잠금하고, 판정은 같은 트랜잭션에 등록한 job이 한다. 게시본이 바뀔 때마다 {@code
 * publication_version}을 1 올린다.
 */
@Component
@EnableConfigurationProperties(PrizeProperties.class)
public class ResultPublisher {

  /** 게시본 판정 job 종류. 중복 키는 게시본 ID다(05 7절 3번). */
  public static final String EVALUATE_RESULT_JOB = "EVALUATE_RESULT";

  private final JdbcTemplate jdbc;
  private final TransactionTemplate tx;
  private final JobStore jobs;
  private final PrizeProperties prizes;
  private final Clock clock;

  /** 게시기를 만든다. */
  public ResultPublisher(
      JdbcTemplate jdbc,
      PlatformTransactionManager transactionManager,
      JobStore jobs,
      PrizeProperties prizes,
      Clock clock) {
    this.jdbc = jdbc;
    this.tx = new TransactionTemplate(transactionManager);
    this.jobs = jobs;
    this.prizes = prizes;
    this.clock = clock;
  }

  /**
   * 결과 API에서 받은 결과를 처리한다. 처음이면 게시하고, 같으면 그대로 두고(직접 등록 게시본이면 API 확인 표시), 다르면 격리한다. 이미 격리한 결과와 같으면 받은
   * 시각만 갱신한다.
   */
  public ReceiveOutcome receive(OfficialDraw draw) {
    return tx.execute(status -> receiveLocked(draw));
  }

  private ReceiveOutcome receiveLocked(OfficialDraw draw) {
    Map<String, Object> round = lockRound(draw.roundNo());
    Object publishedId = round.get("published_result_id");
    if (publishedId == null) {
      long id = insertResult(draw.roundNo(), draw.winning(), draw, "VERIFIED", "API", null);
      insertPrizes(id, draw);
      publish(draw.roundNo(), id, null);
      return ReceiveOutcome.PUBLISHED;
    }
    long published = ((Number) publishedId).longValue();
    Map<String, Object> current =
        jdbc.queryForMap(
            "SELECT semantic_hash, source, api_confirmed_at, numbers_key, bonus_number"
                + " FROM draw_result WHERE id = ?",
            published);
    if (MessageDigest.isEqual((byte[]) current.get("semantic_hash"), draw.semanticHash())) {
      if ("MANUAL".equals(current.get("source")) && current.get("api_confirmed_at") == null) {
        jdbc.update("UPDATE draw_result SET api_confirmed_at = NOW(6) WHERE id = ?", published);
      }
      for (RankPrize prize : draw.upperPrizes()) {
        jdbc.update(
            "UPDATE draw_prize SET prize_per_game = COALESCE(prize_per_game, ?),"
                + " winner_count = COALESCE(winner_count, ?) WHERE result_id = ? AND rank_no = ?",
            prize.prizePerGame(),
            prize.winnerCount(),
            published,
            prize.rank());
      }
      return ReceiveOutcome.SAME_AS_PUBLISHED;
    }
    List<Long> quarantined =
        jdbc.queryForList(
            "SELECT id FROM draw_result WHERE round_no = ? AND state = 'QUARANTINED'"
                + " AND semantic_hash = ?",
            Long.class,
            draw.roundNo(),
            draw.semanticHash());
    if (!quarantined.isEmpty()) {
      jdbc.update("UPDATE draw_result SET fetched_at = ? WHERE id = ?", now(), quarantined.get(0));
      return ReceiveOutcome.ALREADY_QUARANTINED;
    }
    long id = insertResult(draw.roundNo(), draw.winning(), draw, "QUARANTINED", "API", null);
    insertPrizes(id, draw);
    jobs.enqueue(
        JobStore.OPERATOR_ALERT,
        "result-quarantined:" + id,
        "{\"kind\":\"RESULT_QUARANTINED\",\"roundNo\":"
            + draw.roundNo()
            + ",\"resultId\":"
            + id
            + ",\"changed\":"
            + changedFields(current, draw)
            + "}",
        clock.instant());
    return ReceiveOutcome.QUARANTINED;
  }

  private static String changedFields(Map<String, Object> published, OfficialDraw draw) {
    List<String> changed = new ArrayList<>();
    if (!draw.winning().main().key().equals(published.get("numbers_key"))) {
      changed.add("\"NUMBERS\"");
    }
    if (draw.winning().bonus().value() != ((Number) published.get("bonus_number")).intValue()) {
      changed.add("\"BONUS\"");
    }
    return "[" + String.join(",", changed) + "]";
  }

  /**
   * ADMIN의 결과 직접 등록(D-034, 05 6절 직접 등록). 권한·재인증·감사는 호출한 쪽에서 한다. 같은 트랜잭션에 운영자 알림 job을 등록한다(번호는 넣지
   * 않는다, 07 2.1절).
   *
   * @return 새 게시본 ID
   * @throws BusinessException 이미 게시됐으면 {@code RESULT_ALREADY_PUBLISHED}, 게시 버전이 다르면 {@code
   *     VERSION_CONFLICT}, 추첨 시각 전이면 {@code DRAW_NOT_FINISHED}
   */
  public long registerManual(
      int roundNo, WinningNumbers winning, long adminMemberId, long expectedPublicationVersion) {
    return tx.execute(
        status -> {
          Map<String, Object> round = lockRound(roundNo);
          long version = ((Number) round.get("publication_version")).longValue();
          if (round.get("published_result_id") != null) {
            throw new BusinessException(ErrorCode.RESULT_ALREADY_PUBLISHED, "이미 게시된 결과가 있습니다.");
          }
          if (version != expectedPublicationVersion) {
            throw new BusinessException(
                ErrorCode.VERSION_CONFLICT,
                "결과 상태를 다시 확인해 주세요.",
                ErrorDetails.currentVersion(version));
          }
          if (((Number) round.get("draw_pending")).intValue() == 1) {
            throw new BusinessException(ErrorCode.DRAW_NOT_FINISHED, "추첨 전입니다.");
          }
          long id = insertResult(roundNo, winning, null, "VERIFIED", "MANUAL", adminMemberId);
          insertPrizes(id, null);
          publish(roundNo, id, null);
          jobs.enqueue(
              JobStore.OPERATOR_ALERT,
              "result-manual:" + id,
              "{\"kind\":\"RESULT_MANUAL_REGISTERED\",\"roundNo\":" + roundNo + "}",
              clock.instant());
          return id;
        });
  }

  /**
   * 격리한 결과를 새 게시본으로 승인한다. 이전 게시본은 SUPERSEDED가 되고 그 판정 job에 취소를 요청한다. 권한·사유·감사는 호출한 쪽에서 한다.
   *
   * @throws BusinessException 격리된 결과가 아니면 {@code NOT_FOUND}, 게시 버전이 다르면 {@code VERSION_CONFLICT}
   */
  public void approve(long resultId, long expectedPublicationVersion) {
    tx.executeWithoutResult(
        status -> {
          List<Integer> rounds =
              jdbc.queryForList(
                  "SELECT round_no FROM draw_result WHERE id = ? AND state = 'QUARANTINED'",
                  Integer.class,
                  resultId);
          if (rounds.isEmpty()) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "요청한 내용을 찾을 수 없습니다.");
          }
          int roundNo = rounds.get(0);
          Map<String, Object> round = lockRound(roundNo);
          long version = ((Number) round.get("publication_version")).longValue();
          if (version != expectedPublicationVersion) {
            throw new BusinessException(
                ErrorCode.VERSION_CONFLICT,
                "결과 상태를 다시 확인해 주세요.",
                ErrorDetails.currentVersion(version));
          }
          Long previous =
              round.get("published_result_id") == null
                  ? null
                  : ((Number) round.get("published_result_id")).longValue();
          if (previous != null) {
            jdbc.update("UPDATE draw_result SET state = 'SUPERSEDED' WHERE id = ?", previous);
          }
          jdbc.update(
              "UPDATE draw_result SET state = 'VERIFIED', published_at = NOW(6) WHERE id = ?",
              resultId);
          publish(roundNo, resultId, previous);
        });
  }

  private void publish(int roundNo, long resultId, Long previous) {
    jdbc.update(
        "UPDATE lotto_round SET published_result_id = ?, publication_version ="
            + " publication_version + 1 WHERE round_no = ?",
        resultId,
        roundNo);
    if (previous != null) {
      jdbc.update(
          "UPDATE job SET cancel_requested = TRUE WHERE job_type = ? AND dedupe_key = ?"
              + " AND state IN ('READY','RUNNING','RETRY_WAIT')",
          EVALUATE_RESULT_JOB,
          ("result:" + previous).getBytes(StandardCharsets.UTF_8));
    }
    jobs.enqueue(
        EVALUATE_RESULT_JOB,
        "result:" + resultId,
        "{\"resultId\":" + resultId + ",\"roundNo\":" + roundNo + "}",
        clock.instant());
  }

  private long insertResult(
      int roundNo,
      WinningNumbers winning,
      OfficialDraw draw,
      String state,
      String source,
      Long adminMemberId) {
    long revision =
        jdbc.queryForObject(
            "SELECT COALESCE(MAX(revision), 0) + 1 FROM draw_result WHERE round_no = ?",
            Long.class,
            roundNo);
    UUID publicId = UUID.randomUUID();
    OfficialDraw semantic =
        draw != null ? draw : new OfficialDraw(roundNo, winning, null, null, new byte[32]);
    jdbc.update(
        "INSERT INTO draw_result (public_id, round_no, revision, numbers_key, bonus_number,"
            + " semantic_hash, source_hash, state, source, registered_by_member_id, fetched_at,"
            + " published_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW(6), "
            + ("VERIFIED".equals(state) ? "NOW(6)" : "NULL")
            + ")",
        bytes(publicId),
        roundNo,
        revision,
        winning.main().key(),
        winning.bonus().value(),
        semantic.semanticHash(),
        semantic.sourceHash(),
        state,
        source,
        adminMemberId);
    return jdbc.queryForObject(
        "SELECT id FROM draw_result WHERE public_id = ?", Long.class, bytes(publicId));
  }

  private void insertPrizes(long resultId, OfficialDraw draw) {
    Object[][] rows = {
      upper(draw, 1),
      upper(draw, 2),
      upper(draw, 3),
      {4, prizes.fourth(), null},
      {5, prizes.fifth(), null}
    };
    for (Object[] r : rows) {
      jdbc.update(
          "INSERT INTO draw_prize (result_id, rank_no, prize_per_game, winner_count)"
              + " VALUES (?, ?, ?, ?)",
          resultId,
          r[0],
          r[1],
          r[2]);
    }
  }

  private static Object[] upper(OfficialDraw draw, int rank) {
    if (draw == null) {
      return new Object[] {rank, null, null};
    }
    RankPrize prize = draw.prize(rank);
    return new Object[] {rank, prize.prizePerGame(), prize.winnerCount()};
  }

  private Map<String, Object> lockRound(int roundNo) {
    List<Map<String, Object>> rows =
        jdbc.queryForList(
            "SELECT published_result_id, publication_version, draw_at > NOW(6) AS draw_pending"
                + " FROM lotto_round WHERE round_no = ? FOR UPDATE",
            roundNo);
    if (rows.isEmpty()) {
      throw new BusinessException(ErrorCode.NOT_FOUND, "요청한 내용을 찾을 수 없습니다.");
    }
    return rows.get(0);
  }

  private LocalDateTime now() {
    return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
  }
}
