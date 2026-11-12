package com.lottery.lottery_service.record.web;

import com.lottery.lottery_service.common.error.BusinessException;
import com.lottery.lottery_service.common.error.ErrorCode;
import com.lottery.lottery_service.common.number.LottoNumbers;
import com.lottery.lottery_service.common.web.CanonicalUuid;
import com.lottery.lottery_service.common.web.RequestSubject;
import com.lottery.lottery_service.record.PurchaseCommandService;
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

/** 구매 등록({@code POST /me/lotto/purchases}). 회원만 쓴다. 같은 요청을 다시 보내면 처음 결과를 준다(D-057). */
@RestController
@RequestMapping("/api/v1")
@ConditionalOnProperty(prefix = "lottery.api-v1", name = "enabled", havingValue = "true")
public class PurchaseController {

  private final PurchaseCommandService purchases;
  private final Clock clock;

  /** 컨트롤러를 만든다. */
  public PurchaseController(PurchaseCommandService purchases, Clock clock) {
    this.purchases = purchases;
    this.clock = clock;
  }

  /** 구매 등록을 처리한다. */
  @PostMapping("/me/lotto/purchases")
  public ResponseEntity<Success> register(
      Authentication auth,
      @RequestHeader("X-Command-Token") String commandToken,
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @RequestBody Input input) {
    RequestSubject subject = RequestSubject.member(auth);
    if (input.round() == null || input.entries() == null || commandToken.length() > 4096) {
      throw new BusinessException(ErrorCode.VALIDATION_FAILED, "입력을 확인해 주세요.");
    }
    if (input.entries().isEmpty() || input.entries().size() > 100) {
      throw new BusinessException(ErrorCode.VALIDATION_FAILED, "한 번에 1~100세트까지 등록할 수 있습니다.");
    }
    UUID key;
    try {
      key = CanonicalUuid.parse(idempotencyKey);
    } catch (IllegalArgumentException e) {
      throw new BusinessException(ErrorCode.VALIDATION_FAILED, "입력 형식을 확인해 주세요.");
    }
    List<PurchaseCommandService.Entry> entries = new ArrayList<>();
    for (EntryInput entry : input.entries()) {
      if (entry == null || entry.numbers() == null) {
        throw new BusinessException(ErrorCode.VALIDATION_FAILED, "입력을 확인해 주세요.");
      }
      entries.add(
          new PurchaseCommandService.Entry(entry.clientItemId(), LottoNumbers.of(entry.numbers())));
    }
    PurchaseCommandService.Registered result =
        purchases.register(
            subject.subjectId(),
            subject.subjectPublicId(),
            commandToken,
            key,
            input.round(),
            entries);
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(new Success(result.operationId(), "SUCCEEDED", result.data(), clock.instant()));
  }

  /**
   * 요청 본문(openapi {@code PurchaseInput}).
   *
   * @param round 회차
   * @param entries 입력 항목
   */
  public record Input(Integer round, List<EntryInput> entries) {}

  /**
   * 입력 항목(openapi {@code PurchaseEntry}).
   *
   * @param clientItemId 화면이 붙인 항목 ID
   * @param numbers 번호
   */
  public record EntryInput(String clientItemId, List<Integer> numbers) {}

  /**
   * 응답(openapi {@code PurchaseSuccess}).
   *
   * @param operationId 명령 ID
   * @param status SUCCEEDED
   * @param data 항목별 결과
   * @param serverTime 서버 시각
   */
  public record Success(
      UUID operationId,
      String status,
      PurchaseCommandService.PurchaseData data,
      Instant serverTime) {}
}
