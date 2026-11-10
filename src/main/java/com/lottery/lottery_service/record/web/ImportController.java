package com.lottery.lottery_service.record.web;

import com.lottery.lottery_service.common.error.BusinessException;
import com.lottery.lottery_service.common.error.ErrorCode;
import com.lottery.lottery_service.common.web.RequestSubject;
import com.lottery.lottery_service.record.GuestClaimQuery;
import com.lottery.lottery_service.record.ImportCommandService;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 비회원 귀속 목록({@code GET /me/guest-claims})과 가져오기({@code POST /me/guest-claims/{claimId}/imports}).
 * 가져오기는 202와 명령 조회 주소를 준다(D-058).
 */
@RestController
@RequestMapping("/api/v1")
@ConditionalOnProperty(prefix = "lottery.api-v1", name = "enabled", havingValue = "true")
public class ImportController {

  private final ImportCommandService imports;
  private final GuestClaimQuery claims;
  private final Clock clock;

  /** 컨트롤러를 만든다. */
  public ImportController(ImportCommandService imports, GuestClaimQuery claims, Clock clock) {
    this.imports = imports;
    this.claims = claims;
    this.clock = clock;
  }

  /** 아직 가져오지 않은 비회원 귀속 목록. 회원 본인 것만 준다. */
  @GetMapping("/me/guest-claims")
  public ResponseEntity<Map<String, Object>> pending(Authentication auth) {
    RequestSubject subject = RequestSubject.member(auth);
    List<Map<String, Object>> items = new ArrayList<>();
    for (GuestClaimQuery.GuestClaimView claim : claims.pending(subject.subjectId())) {
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("claimId", claim.claimId());
      item.put("recordCount", claim.recordCount());
      item.put("expiresAt", claim.expiresAt());
      items.add(item);
    }
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(Map.of("items", items, "serverTime", clock.instant()));
  }

  /** 가져오기를 시작한다. */
  @PostMapping("/me/guest-claims/{claimId}/imports")
  public ResponseEntity<Map<String, Object>> start(
      Authentication auth,
      @PathVariable("claimId") String claimId,
      @RequestHeader("X-Command-Token") String commandToken,
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @RequestBody Input input) {
    RequestSubject subject = RequestSubject.member(auth);
    if (input == null || !Boolean.TRUE.equals(input.confirm()) || commandToken.length() > 4096) {
      throw new BusinessException(ErrorCode.VALIDATION_FAILED, "가져오기 확인이 필요합니다.");
    }
    UUID claim;
    try {
      claim = UUID.fromString(claimId);
    } catch (IllegalArgumentException e) {
      throw new BusinessException(ErrorCode.NOT_FOUND, "요청한 내용을 찾을 수 없습니다.");
    }
    UUID key;
    try {
      key = UUID.fromString(idempotencyKey);
    } catch (IllegalArgumentException e) {
      throw new BusinessException(ErrorCode.VALIDATION_FAILED, "입력 형식을 확인해 주세요.");
    }
    UUID operation =
        imports.start(subject.subjectId(), subject.subjectPublicId(), commandToken, key, claim);
    return ResponseEntity.status(HttpStatus.ACCEPTED)
        .cacheControl(CacheControl.noStore())
        .body(
            Map.of(
                "operationId",
                operation,
                "status",
                "PROCESSING",
                "data",
                Map.of("pollUrl", "/api/v1/operations/" + operation),
                "serverTime",
                clock.instant()));
  }

  /**
   * 요청 본문(openapi {@code ImportInput}).
   *
   * @param confirm 사용자가 가져오기를 확인했으면 {@code true}
   */
  public record Input(Boolean confirm) {}
}
