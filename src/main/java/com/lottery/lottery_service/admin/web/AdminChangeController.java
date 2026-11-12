package com.lottery.lottery_service.admin.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lottery.lottery_service.admin.AdminCommandRunner;
import com.lottery.lottery_service.admin.AdminOperations;
import com.lottery.lottery_service.auth.MemberPrincipal;
import com.lottery.lottery_service.auth.Reauthentication;
import com.lottery.lottery_service.common.db.DbValues;
import com.lottery.lottery_service.common.error.BusinessException;
import com.lottery.lottery_service.common.error.ErrorCode;
import com.lottery.lottery_service.common.number.LottoNumber;
import com.lottery.lottery_service.common.number.LottoNumbers;
import com.lottery.lottery_service.common.web.CanonicalUuid;
import com.lottery.lottery_service.result.domain.WinningNumbers;
import com.lottery.lottery_service.result.query.RoundResultQuery;
import com.lottery.lottery_service.result.web.RoundResultController;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관리자 변경 API(06 4절): 수동 다시 보내기, 격리 결과 승인, 결과 직접 등록. 권한·사유·감사는 {@link AdminOperations}가, 같은 요청 키의
 * 재전송은 {@link AdminCommandRunner}가 맡는다. 관리자 세션은 앞의 확인기가 본다. 응답과 감사에 번호 조합을 넣지 않는다(결과 응답의 당첨 번호는 공개
 * 정보다).
 */
@RestController
@RequestMapping("/api/v1")
@ConditionalOnProperty(prefix = "lottery.api-v1", name = "enabled", havingValue = "true")
public class AdminChangeController {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final AdminOperations operations;
  private final AdminCommandRunner commands;
  private final RoundResultQuery results;
  private final JdbcTemplate jdbc;
  private final Clock clock;

  /** 컨트롤러를 만든다. */
  public AdminChangeController(
      AdminOperations operations,
      AdminCommandRunner commands,
      RoundResultQuery results,
      JdbcTemplate jdbc,
      Clock clock) {
    this.operations = operations;
    this.commands = commands;
    this.results = results;
    this.jdbc = jdbc;
    this.clock = clock;
  }

  /** 수동 다시 보내기(OPERATOR 이상). 실행을 마친 뒤 202와 실행 ID를 준다. */
  @PostMapping("/admin/rounds/{round}/notification-dispatches")
  public ResponseEntity<Map<String, Object>> dispatch(
      Authentication auth,
      @PathVariable("round") int round,
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @RequestBody ReasonInput input) {
    MemberPrincipal member = member(auth);
    requireRound(round);
    String reason = reason(input == null ? null : input.reason());
    String summary =
        commands.run(
            member.subjectId(),
            "ADMIN_NOTIFICATION_RESEND",
            key(idempotencyKey),
            round + "|" + reason,
            false,
            operationId -> {
              operations.resendNotifications(member.memberId(), round, reason);
              return "{\"dispatchId\":\""
                  + operationId
                  + "\",\"round\":"
                  + round
                  + ",\"acceptedAt\":\""
                  + clock.instant()
                  + "\"}";
            });
    JsonNode stored = read(summary);
    return ResponseEntity.status(HttpStatus.ACCEPTED)
        .cacheControl(CacheControl.noStore())
        .body(
            Map.of(
                "dispatchId", stored.path("dispatchId").asText(),
                "round", stored.path("round").asInt(),
                "acceptedAt", stored.path("acceptedAt").asText()));
  }

  /** 격리된 결과를 새 게시본으로 승인한다(ADMIN). 응답은 그 회차의 지금 결과다. */
  @PostMapping("/admin/draw-results/{resultId}/publication")
  public ResponseEntity<RoundResultController.DrawResultResponse> publish(
      Authentication auth,
      @PathVariable("resultId") String resultId,
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @RequestBody PublicationInput input) {
    MemberPrincipal member = member(auth);
    UUID publicId = uuid(resultId, ErrorCode.NOT_FOUND);
    if (input == null) {
      throw invalid();
    }
    long expected = version(input.expectedPublicationVersion());
    String reason = reason(input.reason());
    String summary =
        commands.run(
            member.subjectId(),
            "ADMIN_RESULT_PUBLISH",
            key(idempotencyKey),
            publicId + "|" + expected + "|" + reason,
            true,
            operationId -> {
              List<Map<String, Object>> rows =
                  jdbc.queryForList(
                      "SELECT id, round_no FROM draw_result WHERE public_id = ?",
                      (Object) DbValues.bytes(publicId));
              if (rows.isEmpty()) {
                throw new BusinessException(ErrorCode.NOT_FOUND, "요청한 내용을 찾을 수 없습니다.");
              }
              long id = ((Number) rows.get(0).get("id")).longValue();
              operations.approveResult(member.memberId(), id, expected, reason);
              return "{\"round\":" + ((Number) rows.get(0).get("round_no")).intValue() + "}";
            });
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(current(read(summary).path("round").asInt()));
  }

  /** 결과 직접 등록(ADMIN, D-034). 최근 5분 안 재인증이 필요하다. 201과 그 회차의 지금 결과를 준다. */
  @PostMapping("/admin/rounds/{round}/draw-results")
  public ResponseEntity<RoundResultController.DrawResultResponse> register(
      Authentication auth,
      HttpServletRequest request,
      @PathVariable("round") int round,
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @RequestBody ManualResultInput input) {
    MemberPrincipal member = member(auth);
    requireRound(round);
    if (input == null || input.numbers() == null || input.bonus() == null) {
      throw invalid();
    }
    WinningNumbers winning =
        new WinningNumbers(LottoNumbers.of(input.numbers()), LottoNumber.of(input.bonus()));
    long expected = version(input.expectedPublicationVersion());
    String reason = reason(input.reason());
    String summary =
        commands.run(
            member.subjectId(),
            "ADMIN_RESULT_REGISTER",
            key(idempotencyKey),
            round
                + "|"
                + new TreeSet<>(input.numbers())
                + "|"
                + input.bonus()
                + "|"
                + expected
                + "|"
                + reason,
            true,
            operationId -> {
              operations.registerManualResult(
                  member.memberId(),
                  round,
                  winning,
                  expected,
                  Reauthentication.lastAt(request).orElse(null),
                  reason);
              return "{\"round\":" + round + "}";
            });
    return ResponseEntity.status(HttpStatus.CREATED)
        .cacheControl(CacheControl.noStore())
        .body(current(read(summary).path("round").asInt()));
  }

  private RoundResultController.DrawResultResponse current(int round) {
    return results
        .find(round)
        .map(RoundResultController.DrawResultResponse::of)
        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "요청한 내용을 찾을 수 없습니다."));
  }

  private static MemberPrincipal member(Authentication auth) {
    if (auth == null || !(auth.getPrincipal() instanceof MemberPrincipal member)) {
      throw new BusinessException(ErrorCode.UNAUTHENTICATED, "로그인이 필요합니다.");
    }
    return member;
  }

  private static void requireRound(int round) {
    if (round < 1) {
      throw invalid();
    }
  }

  private static String reason(String reason) {
    if (reason == null || reason.isBlank() || reason.length() > 500) {
      throw new BusinessException(ErrorCode.VALIDATION_FAILED, "사유를 1~500자로 적어 주세요.");
    }
    return reason;
  }

  private static long version(String value) {
    if (value == null || !value.matches("[0-9]{1,18}")) {
      throw invalid();
    }
    return Long.parseLong(value);
  }

  private static UUID key(String value) {
    return uuid(value, ErrorCode.VALIDATION_FAILED);
  }

  private static UUID uuid(String value, ErrorCode onError) {
    try {
      return CanonicalUuid.parse(value);
    } catch (IllegalArgumentException | NullPointerException e) {
      throw new BusinessException(
          onError, onError == ErrorCode.NOT_FOUND ? "요청한 내용을 찾을 수 없습니다." : "입력 형식을 확인해 주세요.");
    }
  }

  private static BusinessException invalid() {
    return new BusinessException(ErrorCode.VALIDATION_FAILED, "입력을 확인해 주세요.");
  }

  private static JsonNode read(String json) {
    try {
      return MAPPER.readTree(json);
    } catch (Exception e) {
      throw new IllegalStateException("invalid admin command summary", e);
    }
  }

  /**
   * 요청 본문(openapi {@code AdminReasonInput}).
   *
   * @param reason 사유(1~500자)
   */
  public record ReasonInput(String reason) {}

  /**
   * 요청 본문(openapi {@code PublicationInput}).
   *
   * @param expectedPublicationVersion 화면이 본 게시 버전(십진 문자열)
   * @param reason 사유(1~500자)
   */
  public record PublicationInput(String expectedPublicationVersion, String reason) {}

  /**
   * 요청 본문(openapi {@code ManualResultInput}).
   *
   * @param numbers 당첨 번호 여섯 개
   * @param bonus 보너스 번호
   * @param expectedPublicationVersion 화면이 본 게시 버전(십진 문자열)
   * @param reason 사유(1~500자)
   */
  public record ManualResultInput(
      List<Integer> numbers, Integer bonus, String expectedPublicationVersion, String reason) {

    @Override
    public String toString() {
      return "ManualResultInput[redacted]";
    }
  }
}
