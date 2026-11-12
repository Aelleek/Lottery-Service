package com.lottery.lottery_service.record.web;

import com.lottery.lottery_service.common.error.BusinessException;
import com.lottery.lottery_service.common.error.ErrorCode;
import com.lottery.lottery_service.common.web.CanonicalUuid;
import com.lottery.lottery_service.common.web.RequestSubject;
import com.lottery.lottery_service.record.CancelCommandService;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 구매 등록 취소({@code POST /me/lotto/records/{recordId}/cancel-purchase}). 회원만 쓴다. 다른 회원의 기록은 404다. 같은
 * 요청을 다시 보내면 번호 없는 같은 완료 증명을 준다.
 */
@RestController
@RequestMapping("/api/v1")
@ConditionalOnProperty(prefix = "lottery.api-v1", name = "enabled", havingValue = "true")
public class CancelPurchaseController {

  private final CancelCommandService cancels;
  private final Clock clock;

  /** 컨트롤러를 만든다. */
  public CancelPurchaseController(CancelCommandService cancels, Clock clock) {
    this.cancels = cancels;
    this.clock = clock;
  }

  /** 취소를 처리한다. */
  @PostMapping("/me/lotto/records/{recordId}/cancel-purchase")
  public ResponseEntity<Success> cancel(
      Authentication auth,
      @PathVariable("recordId") String recordId,
      @RequestHeader("X-Command-Token") String commandToken,
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @RequestBody Input input) {
    RequestSubject subject = RequestSubject.member(auth);
    UUID record;
    try {
      record = CanonicalUuid.parse(recordId);
    } catch (IllegalArgumentException e) {
      throw new BusinessException(ErrorCode.NOT_FOUND, "요청한 내용을 찾을 수 없습니다.");
    }
    UUID key;
    long version;
    try {
      key = CanonicalUuid.parse(idempotencyKey);
      version = Long.parseLong(input.expectedVersion());
    } catch (IllegalArgumentException | NullPointerException e) {
      throw new BusinessException(ErrorCode.VALIDATION_FAILED, "입력 형식을 확인해 주세요.");
    }
    if (commandToken.length() > 4096 || version < 0) {
      throw new BusinessException(ErrorCode.VALIDATION_FAILED, "입력을 확인해 주세요.");
    }
    CancelCommandService.CancelProof proof =
        cancels.cancel(
            subject.subjectId(), subject.subjectPublicId(), commandToken, key, record, version);
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(
            new Success(
                proof.operationId(),
                "SUCCEEDED",
                new Data(proof.outcome(), proof.completedAt()),
                clock.instant()));
  }

  /**
   * 요청 본문(openapi {@code VersionInput}).
   *
   * @param expectedVersion 화면이 본 기록 버전(십진 문자열)
   */
  public record Input(String expectedVersion) {}

  /**
   * 응답(openapi {@code CancelSuccess}).
   *
   * @param operationId 명령 ID
   * @param status SUCCEEDED
   * @param data 완료 증명
   * @param serverTime 서버 시각
   */
  public record Success(UUID operationId, String status, Data data, Instant serverTime) {}

  /**
   * 완료 증명(openapi {@code CancelData}).
   *
   * @param outcome RECOMMENDATION_RETAINED 또는 RECORD_DELETED
   * @param completedAt 처음 처리한 시각
   */
  public record Data(String outcome, Instant completedAt) {}
}
