package com.lottery.lottery_service.recommendation;

import static com.lottery.lottery_service.common.db.DbValues.bytes;

import com.lottery.lottery_service.common.command.CommandOperationStore;
import com.lottery.lottery_service.common.command.CommandStart;
import com.lottery.lottery_service.common.command.CommandToken;
import com.lottery.lottery_service.common.command.CommandTokenCodec;
import com.lottery.lottery_service.common.db.DbValues;
import com.lottery.lottery_service.common.error.BusinessException;
import com.lottery.lottery_service.common.error.ErrorCode;
import com.lottery.lottery_service.common.number.LottoNumbers;
import com.lottery.lottery_service.recommendation.generator.RecommendationCondition;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 추천 명령 하나를 멱등하게 처리한다(06 1.1절). 같은 토큰·키·본문을 다시 보내면 새 추천이 아니라 처음 5세트를 돌려준다. 원본이 지워졌으면 {@code
 * RESPONSE_EXPIRED}다.
 */
@Component
public class RecommendationCommandService {

  /** 추천 명령 종류. 명령 토큰에 묶인다. */
  public static final String OPERATION = "RECOMMEND";

  private static final Set<ErrorCode> RETRYABLE =
      EnumSet.of(ErrorCode.GENERATION_BUSY, ErrorCode.GENERATION_TIMEOUT);

  private final CommandTokenCodec tokens;
  private final CommandOperationStore operations;
  private final RecommendationService recommendations;
  private final JdbcTemplate jdbc;

  /** 서비스를 만든다. */
  public RecommendationCommandService(
      CommandTokenCodec tokens,
      CommandOperationStore operations,
      RecommendationService recommendations,
      JdbcTemplate jdbc) {
    this.tokens = tokens;
    this.operations = operations;
    this.recommendations = recommendations;
    this.jdbc = jdbc;
  }

  /**
   * 추천 명령을 처리한다.
   *
   * @param subjectId 세션이나 비회원 쿠키에서 구한 주체 ID
   * @param subjectPublicId 그 주체의 외부 ID
   * @param commandToken {@code X-Command-Token}
   * @param idempotencyKey {@code Idempotency-Key}
   * @param condition 요청 본문의 조건
   * @param requestedRound 요청 본문의 회차
   * @param requestedScheduleVersion 요청 본문의 일정 버전
   * @throws CommandInProgressException 같은 요청이 처리 중일 때
   */
  public RecommendationView recommend(
      long subjectId,
      UUID subjectPublicId,
      String commandToken,
      UUID idempotencyKey,
      RecommendationCondition condition,
      int requestedRound,
      long requestedScheduleVersion) {
    CommandToken token = tokens.verify(commandToken, subjectPublicId, OPERATION);
    if (token.roundNo() == null
        || token.roundNo() != requestedRound
        || token.scheduleVersion() != requestedScheduleVersion) {
      throw new BusinessException(ErrorCode.FORBIDDEN, "허용되지 않은 요청입니다.");
    }
    byte[] requestHash = requestHash(requestedRound, requestedScheduleVersion, condition);
    CommandStart start = operations.begin(subjectId, token, idempotencyKey, requestHash);
    if (start instanceof CommandStart.Completed completed) {
      return load(completed.publicId(), subjectId, completed.resourcePublicId());
    }
    if (start instanceof CommandStart.InProgress progress) {
      throw new CommandInProgressException(progress.publicId());
    }
    if (start instanceof CommandStart.Failed failed) {
      throw new BusinessException(ErrorCode.valueOf(failed.completionCode()), "요청을 처리하지 못했습니다.");
    }
    CommandStart.Started started = (CommandStart.Started) start;
    try {
      SavedRecommendation saved =
          recommendations.recommend(
              new RecommendCommand(
                  subjectId,
                  requestedRound,
                  requestedScheduleVersion,
                  condition,
                  keyDigest(token.tokenId(), idempotencyKey),
                  requestHash),
              s -> operations.succeed(started, s.publicId(), "CREATED"));
      return load(started.publicId(), subjectId, saved.publicId());
    } catch (BusinessException e) {
      if (RETRYABLE.contains(e.getCode())) {
        operations.markRetryable(started, e.getCode().name());
      } else {
        operations.markFailed(started, e.getCode().name());
      }
      throw e;
    } catch (GenerationSupersededException e) {
      operations.markRetryable(started, ErrorCode.TEMPORARILY_UNAVAILABLE.name());
      throw new BusinessException(ErrorCode.TEMPORARILY_UNAVAILABLE, "잠시 뒤 다시 시도해 주세요.");
    } catch (RuntimeException e) {
      operations.markRetryable(started, ErrorCode.TEMPORARILY_UNAVAILABLE.name());
      throw e;
    }
  }

  private RecommendationView load(UUID operationId, long subjectId, UUID requestPublicId) {
    List<Map<String, Object>> rows =
        jdbc.queryForList(
            "SELECT r.subject_id, r.round_no, r.generation_mode, r.filter_version,"
                + " r.response_expires_at, i.numbers_key, l.public_id AS record_public_id"
                + " FROM recommendation_request r"
                + " JOIN recommendation_item i ON i.request_id = r.id"
                + " LEFT JOIN lotto_record l ON l.id = i.record_id AND l.deleted_at IS NULL"
                + " WHERE r.public_id = ? ORDER BY i.ordinal",
            bytes(requestPublicId));
    if (rows.size() != 5) {
      throw new BusinessException(ErrorCode.RESPONSE_EXPIRED, "추천 원본 보관 기간이 지났습니다.");
    }
    Map<String, Object> first = rows.get(0);
    if (((Number) first.get("subject_id")).longValue() != subjectId) {
      throw new BusinessException(ErrorCode.NOT_FOUND, "요청한 내용을 찾을 수 없습니다.");
    }
    List<UUID> recordIds = new ArrayList<>();
    for (Map<String, Object> row : rows) {
      recordIds.add(DbValues.uuid((byte[]) row.get("record_public_id")));
    }
    return new RecommendationView(
        operationId,
        requestPublicId,
        ((Number) first.get("round_no")).intValue(),
        rows.stream().map(r -> LottoNumbers.fromKey((String) r.get("numbers_key"))).toList(),
        recordIds,
        (String) first.get("generation_mode"),
        (String) first.get("filter_version"),
        DbValues.instant((LocalDateTime) first.get("response_expires_at")));
  }

  static byte[] requestHash(int round, long scheduleVersion, RecommendationCondition condition) {
    String canonical =
        round
            + "|"
            + scheduleVersion
            + "|"
            + new TreeSet<>(condition.include())
            + "|"
            + new TreeSet<>(condition.exclude());
    return sha256(canonical.getBytes(StandardCharsets.UTF_8));
  }

  private static byte[] keyDigest(UUID tokenId, UUID idempotencyKey) {
    return sha256(ByteBuffer.allocate(32).put(bytes(tokenId)).put(bytes(idempotencyKey)).array());
  }

  private static byte[] sha256(byte[] value) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(value);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
