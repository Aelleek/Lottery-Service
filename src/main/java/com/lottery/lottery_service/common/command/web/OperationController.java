package com.lottery.lottery_service.common.command.web;

import com.lottery.lottery_service.common.command.OperationQuery;
import com.lottery.lottery_service.common.error.BusinessException;
import com.lottery.lottery_service.common.error.ErrorCode;
import com.lottery.lottery_service.common.web.CanonicalUuid;
import com.lottery.lottery_service.common.web.RequestSubject;
import com.lottery.lottery_service.recommendation.RecommendationView;
import com.lottery.lottery_service.recommendation.web.RecommendationController;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 이전 명령의 결과 조회({@code GET /operations/{id}}). 다른 주체의 명령은 404, 처리 중은 200과 {@code PROCESSING}(openapi
 * {@code OperationResult}의 {@code Accepted}), 확정 실패는 그 오류 응답이다(06 1.1절).
 */
@RestController
@RequestMapping("/api/v1")
@ConditionalOnProperty(prefix = "lottery.api-v1", name = "enabled", havingValue = "true")
public class OperationController {

  private final OperationQuery operations;
  private final Clock clock;

  /** 컨트롤러를 만든다. */
  public OperationController(OperationQuery operations, Clock clock) {
    this.operations = operations;
    this.clock = clock;
  }

  /** 명령 결과를 준다. */
  @GetMapping("/operations/{operationId}")
  public ResponseEntity<Map<String, Object>> find(
      Authentication auth, @PathVariable("operationId") String operationId) {
    RequestSubject subject = RequestSubject.any(auth);
    UUID id;
    try {
      id = CanonicalUuid.parse(operationId);
    } catch (IllegalArgumentException e) {
      throw new BusinessException(ErrorCode.NOT_FOUND, "요청한 내용을 찾을 수 없습니다.");
    }
    OperationQuery.Outcome outcome = operations.find(subject.subjectId(), id);
    if (outcome instanceof OperationQuery.Outcome.Processing) {
      return ResponseEntity.ok()
          .cacheControl(CacheControl.noStore())
          .body(
              Map.of(
                  "operationId",
                  id,
                  "status",
                  "PROCESSING",
                  "data",
                  Map.of("pollUrl", "/api/v1/operations/" + id),
                  "serverTime",
                  clock.instant()));
    }
    Object data = ((OperationQuery.Outcome.Done) outcome).data();
    if (data instanceof RecommendationView view) {
      data = RecommendationController.toData(view);
    }
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(
            Map.of(
                "operationId",
                id,
                "status",
                "SUCCEEDED",
                "data",
                data,
                "serverTime",
                clock.instant()));
  }
}
