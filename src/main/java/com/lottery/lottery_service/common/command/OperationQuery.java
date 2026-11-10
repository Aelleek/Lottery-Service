package com.lottery.lottery_service.common.command;

import static com.lottery.lottery_service.common.db.DbValues.bytes;
import static com.lottery.lottery_service.common.db.DbValues.uuid;

import com.lottery.lottery_service.common.error.BusinessException;
import com.lottery.lottery_service.common.error.ErrorCode;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * {@code GET /operations/{id}}: 이전 명령의 처리 결과(06 1.1절, 2절). 다른 주체의 기록은 존재 여부도 알 수 없게 {@code
 * NOT_FOUND}, 처리 중이면 진행 상태, 확정 실패면 그 오류, 끝났으면 명령 종류의 로더가 처음 결과를 다시 읽는다(원본이 지워졌으면 로더가 {@code
 * RESPONSE_EXPIRED}).
 */
@Component
public class OperationQuery {

  private final JdbcTemplate jdbc;
  private final Map<String, CommandResultLoader> loaders;

  /** 조회기를 만든다. */
  public OperationQuery(JdbcTemplate jdbc, List<CommandResultLoader> loaders) {
    this.jdbc = jdbc;
    this.loaders =
        loaders.stream()
            .collect(Collectors.toMap(CommandResultLoader::operation, Function.identity()));
  }

  /**
   * 명령 결과를 읽는다.
   *
   * @param subjectId 세션이나 비회원 쿠키에서 구한 주체 ID
   * @param operationPublicId 경로의 명령 외부 ID
   * @throws BusinessException 없거나 다른 주체의 것이면 {@code NOT_FOUND}, 확정 실패면 그 오류 코드, 만료됐으면 {@code
   *     COMMAND_EXPIRED}
   */
  public Outcome find(long subjectId, UUID operationPublicId) {
    List<Map<String, Object>> rows =
        jdbc.queryForList(
            "SELECT subject_id, operation, state, resource_public_id, completion_code"
                + " FROM command_operation WHERE public_id = ?",
            bytes(operationPublicId));
    if (rows.isEmpty() || ((Number) rows.get(0).get("subject_id")).longValue() != subjectId) {
      throw new BusinessException(ErrorCode.NOT_FOUND, "요청한 내용을 찾을 수 없습니다.");
    }
    Map<String, Object> row = rows.get(0);
    String state = (String) row.get("state");
    return switch (state) {
      case "PROCESSING", "RETRYABLE" -> new Outcome.Processing(operationPublicId);
      case "FAILED" -> throw new BusinessException(
          ErrorCode.valueOf((String) row.get("completion_code")), "요청을 처리하지 못했습니다.");
      case "EXPIRED" -> throw new BusinessException(ErrorCode.COMMAND_EXPIRED, "요청이 만료됐습니다.");
      default -> {
        CommandResultLoader loader = loaders.get((String) row.get("operation"));
        if (loader == null) {
          throw new IllegalStateException("no result loader: " + row.get("operation"));
        }
        yield new Outcome.Done(
            operationPublicId,
            loader.load(
                subjectId,
                operationPublicId,
                uuid((byte[]) row.get("resource_public_id")),
                (String) row.get("completion_code")));
      }
    };
  }

  /** 명령 결과. */
  public sealed interface Outcome {

    /**
     * 아직 처리 중이다(202 {@code PROCESSING}).
     *
     * @param operationId 명령 외부 ID
     */
    record Processing(UUID operationId) implements Outcome {}

    /**
     * 끝났다(200 {@code SUCCEEDED}).
     *
     * @param operationId 명령 외부 ID
     * @param data 처음 결과
     */
    record Done(UUID operationId, Object data) implements Outcome {}
  }
}
