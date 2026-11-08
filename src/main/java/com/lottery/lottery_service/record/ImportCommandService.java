package com.lottery.lottery_service.record;

import static com.lottery.lottery_service.common.db.DbValues.bytes;

import com.lottery.lottery_service.common.command.CommandOperationStore;
import com.lottery.lottery_service.common.command.CommandResultLoader;
import com.lottery.lottery_service.common.command.CommandStart;
import com.lottery.lottery_service.common.command.CommandToken;
import com.lottery.lottery_service.common.command.CommandTokenCodec;
import com.lottery.lottery_service.common.error.BusinessException;
import com.lottery.lottery_service.common.error.ErrorCode;
import com.lottery.lottery_service.recommendation.CommandInProgressException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 비회원 기록 가져오기 명령(05 4절 4번, D-058). 귀속 전체를 가져오는 job을 등록하고 명령은 바로 끝낸다. 결과는 명령 조회로 보고, job이 끝나기 전에는 처리
 * 중(202)으로 답한다. 같은 귀속은 job이 한 번만 등록된다.
 */
@Component
public class ImportCommandService implements CommandResultLoader {

  /** 가져오기 명령 종류. */
  public static final String OPERATION = "IMPORT_GUEST";

  /** 완료 코드. job을 등록했다는 뜻이다. */
  public static final String STARTED = "IMPORT_STARTED";

  private final CommandTokenCodec tokens;
  private final CommandOperationStore operations;
  private final HistoryImportJobHandler jobs;
  private final JdbcTemplate jdbc;
  private final TransactionTemplate tx;

  /** 서비스를 만든다. */
  public ImportCommandService(
      CommandTokenCodec tokens,
      CommandOperationStore operations,
      HistoryImportJobHandler jobs,
      JdbcTemplate jdbc,
      PlatformTransactionManager transactionManager) {
    this.tokens = tokens;
    this.operations = operations;
    this.jobs = jobs;
    this.jdbc = jdbc;
    this.tx = new TransactionTemplate(transactionManager);
  }

  /**
   * 가져오기를 시작한다.
   *
   * @return 명령 외부 ID. 결과는 명령 조회로 본다
   * @throws BusinessException 회원 본인의 귀속이 아니면 {@code NOT_FOUND}
   * @throws CommandInProgressException 같은 요청이 처리 중일 때
   */
  public UUID start(
      long subjectId,
      UUID subjectPublicId,
      String commandToken,
      UUID idempotencyKey,
      UUID claimPublicId) {
    CommandToken token = tokens.verify(commandToken, subjectPublicId, OPERATION);
    CommandStart start =
        operations.begin(subjectId, token, idempotencyKey, requestHash(claimPublicId));
    if (start instanceof CommandStart.Completed completed) {
      return completed.publicId();
    }
    if (start instanceof CommandStart.InProgress progress) {
      throw new CommandInProgressException(progress.publicId());
    }
    if (start instanceof CommandStart.Failed failed) {
      throw new BusinessException(ErrorCode.valueOf(failed.completionCode()), "요청을 처리하지 못했습니다.");
    }
    CommandStart.Started started = (CommandStart.Started) start;
    try {
      tx.executeWithoutResult(
          status -> {
            long claimId = ownClaim(subjectId, claimPublicId);
            jobs.start(subjectId, claimId);
            operations.succeed(started, claimPublicId, STARTED);
          });
      return started.publicId();
    } catch (BusinessException e) {
      operations.markFailed(started, e.getCode().name());
      throw e;
    } catch (RuntimeException e) {
      operations.markRetryable(started, ErrorCode.TEMPORARILY_UNAVAILABLE.name());
      throw e;
    }
  }

  @Override
  public String operation() {
    return OPERATION;
  }

  /**
   * 가져오기 결과(openapi {@code ImportResult}). job이 아직 끝나지 않았으면 처리 중이다.
   *
   * @throws CommandInProgressException job이 끝나지 않았을 때
   */
  @Override
  public ImportResult load(
      long subjectId, UUID operationPublicId, UUID resourcePublicId, String completionCode) {
    long claimId = ownClaim(subjectId, resourcePublicId);
    List<String> states =
        jdbc.queryForList(
            "SELECT state FROM job WHERE job_type = ? AND dedupe_key = ?",
            String.class,
            HistoryImportJobHandler.JOB,
            ("claim:" + claimId).getBytes(StandardCharsets.UTF_8));
    String state = states.isEmpty() ? "SUCCEEDED" : states.get(0);
    if (!"SUCCEEDED".equals(state) && !"DEAD".equals(state) && !"CANCELED".equals(state)) {
      throw new CommandInProgressException(operationPublicId);
    }
    Map<String, Object> counts =
        jdbc.queryForMap(
            "SELECT COALESCE(SUM(outcome = 'CREATED'), 0) AS created,"
                + " COALESCE(SUM(outcome = 'MERGED'), 0) AS merged,"
                + " COALESCE(SUM(outcome LIKE 'SKIPPED%'), 0) AS skipped"
                + " FROM history_import_item WHERE claim_id = ?",
            claimId);
    List<Integer> late =
        jdbc.queryForList(
            "SELECT DISTINCT t.round_no FROM history_import_item i"
                + " JOIN lotto_record t ON t.id = i.target_record_id"
                + " JOIN lotto_round r ON r.round_no = t.round_no"
                + " JOIN subject_claim c ON c.id = i.claim_id"
                + " WHERE i.claim_id = ? AND i.outcome IN ('CREATED','MERGED')"
                + " AND c.imported_at IS NOT NULL AND c.imported_at >= COALESCE(r.cutoff_close_at, r.sales_close_at)"
                + " ORDER BY t.round_no",
            Integer.class,
            claimId);
    return new ImportResult(
        "SUCCEEDED".equals(state) ? "COMPLETE" : "FAILED",
        ((Number) counts.get("created")).intValue(),
        ((Number) counts.get("merged")).intValue(),
        ((Number) counts.get("skipped")).intValue(),
        late);
  }

  private long ownClaim(long memberSubjectId, UUID claimPublicId) {
    List<Long> found =
        jdbc.queryForList(
            "SELECT id FROM subject_claim WHERE public_id = ? AND member_subject_id = ?",
            Long.class,
            bytes(claimPublicId),
            memberSubjectId);
    if (found.isEmpty()) {
      throw new BusinessException(ErrorCode.NOT_FOUND, "요청한 내용을 찾을 수 없습니다.");
    }
    return found.get(0);
  }

  private static byte[] requestHash(UUID claimPublicId) {
    try {
      return MessageDigest.getInstance("SHA-256")
          .digest(("import|" + claimPublicId).getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * 가져오기 결과(openapi {@code ImportResult}).
   *
   * @param outcome COMPLETE 또는 FAILED
   * @param createdCount 새로 만든 기록 수
   * @param mergedCount 회원 기록에 합친 수
   * @param skippedCount 건너뛴 수
   * @param lateImportRounds 판매 마감 뒤에 가져온 회차
   */
  public record ImportResult(
      String outcome,
      int createdCount,
      int mergedCount,
      int skippedCount,
      List<Integer> lateImportRounds) {}
}
