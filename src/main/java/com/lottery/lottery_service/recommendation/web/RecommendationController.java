package com.lottery.lottery_service.recommendation.web;

import com.lottery.lottery_service.auth.GuestPrincipal;
import com.lottery.lottery_service.auth.MemberPrincipal;
import com.lottery.lottery_service.common.error.BusinessException;
import com.lottery.lottery_service.common.error.ErrorCode;
import com.lottery.lottery_service.recommendation.RecommendationCommandService;
import com.lottery.lottery_service.recommendation.RecommendationView;
import com.lottery.lottery_service.recommendation.generator.RecommendationCondition;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 5세트 추천({@code POST /lotto/recommendations}). 주체는 회원 세션이나 비회원 쿠키에서만 구한다. 같은 토큰·키·본문을 다시 보내면 처음 결과를
 * 준다(06 1.1절). 처리 중이면 202와 조회 주소다.
 */
@RestController
@RequestMapping("/api/v1")
@ConditionalOnProperty(prefix = "lottery.api-v1", name = "enabled", havingValue = "true")
public class RecommendationController {

  private static final int MAX_LIST = 45;

  private final RecommendationCommandService commands;
  private final Clock clock;

  /** 컨트롤러를 만든다. */
  public RecommendationController(RecommendationCommandService commands, Clock clock) {
    this.commands = commands;
    this.clock = clock;
  }

  /** 추천을 만든다. */
  @PostMapping("/lotto/recommendations")
  public ResponseEntity<Success> recommend(
      Authentication auth,
      @RequestHeader("X-Command-Token") String commandToken,
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @RequestBody Input input) {
    Object principal = auth == null ? null : auth.getPrincipal();
    long subjectId;
    UUID subjectPublicId;
    if (principal instanceof MemberPrincipal m) {
      subjectId = m.subjectId();
      subjectPublicId = m.subjectPublicId();
    } else if (principal instanceof GuestPrincipal g) {
      subjectId = g.subjectId();
      subjectPublicId = g.subjectPublicId();
    } else {
      throw new BusinessException(ErrorCode.UNAUTHENTICATED, "로그인이 필요합니다.");
    }
    if (input.round() == null
        || input.scheduleVersion() == null
        || input.include() == null
        || input.exclude() == null
        || input.include().size() > MAX_LIST
        || input.exclude().size() > MAX_LIST
        || commandToken.length() > 4096) {
      throw new BusinessException(ErrorCode.VALIDATION_FAILED, "입력을 확인해 주세요.");
    }
    UUID key;
    long version;
    try {
      key = UUID.fromString(idempotencyKey);
      version = Long.parseLong(input.scheduleVersion());
    } catch (IllegalArgumentException e) {
      throw new BusinessException(ErrorCode.VALIDATION_FAILED, "입력 형식을 확인해 주세요.");
    }
    RecommendationView view =
        commands.recommend(
            subjectId,
            subjectPublicId,
            commandToken,
            key,
            RecommendationCondition.of(input.include(), input.exclude()),
            input.round(),
            version);
    List<RecommendedSet> sets = new ArrayList<>();
    for (int i = 0; i < view.sets().size(); i++) {
      sets.add(new RecommendedSet(view.recordIds().get(i), view.sets().get(i).toList(), null));
    }
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(
            new Success(
                view.operationId(),
                "SUCCEEDED",
                new Data(
                    view.publicId(),
                    view.roundNo(),
                    sets,
                    view.generationMode(),
                    view.filterVersion(),
                    view.responseExpiresAt()),
                clock.instant()));
  }

  /**
   * 요청 본문(openapi {@code RecommendationInput}).
   *
   * @param round 회차
   * @param scheduleVersion 일정 버전(십진 문자열)
   * @param include 넣을 번호
   * @param exclude 뺄 번호
   */
  public record Input(
      Integer round, String scheduleVersion, List<Integer> include, List<Integer> exclude) {}

  /**
   * 응답(openapi {@code RecommendationSuccess}).
   *
   * @param operationId 명령 ID
   * @param status SUCCEEDED
   * @param data 추천 결과
   * @param serverTime 서버 시각
   */
  public record Success(UUID operationId, String status, Data data, Instant serverTime) {}

  /**
   * 추천 결과(openapi {@code RecommendationData}).
   *
   * @param requestId 추천 요청 ID
   * @param round 회차
   * @param sets 5세트
   * @param generationMode BASIC 또는 CONDITION
   * @param filterVersion 생성 필터 버전
   * @param responseExpiresAt 다시 받을 수 있는 기한
   */
  public record Data(
      UUID requestId,
      int round,
      List<RecommendedSet> sets,
      String generationMode,
      String filterVersion,
      Instant responseExpiresAt) {}

  /**
   * 한 세트(openapi {@code RecommendedSet}).
   *
   * @param recordId 기록 ID
   * @param numbers 오름차순 번호
   * @param expiresAt 기록 보관 기한. 아직 주지 않는다
   */
  public record RecommendedSet(UUID recordId, List<Integer> numbers, Instant expiresAt) {}
}
