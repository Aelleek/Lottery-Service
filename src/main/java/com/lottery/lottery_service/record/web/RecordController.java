package com.lottery.lottery_service.record.web;

import com.lottery.lottery_service.common.error.BusinessException;
import com.lottery.lottery_service.common.error.ErrorCode;
import com.lottery.lottery_service.common.web.RequestSubject;
import com.lottery.lottery_service.record.RecordEvaluation;
import com.lottery.lottery_service.record.RecordHistoryQuery;
import com.lottery.lottery_service.record.RecordPage;
import com.lottery.lottery_service.record.RecordView;
import com.lottery.lottery_service.stats.UsageEventStore;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 본인 기록 조회. 회원은 {@code GET /me/lotto/records}, 비회원은 {@code GET /guest/lotto/records}다. 주체는 세션·쿠키에서만
 * 구하고 다른 주체의 기록은 보이지 않는다. 응답은 캐시하지 않는다.
 */
@RestController
@RequestMapping("/api/v1")
@ConditionalOnProperty(prefix = "lottery.api-v1", name = "enabled", havingValue = "true")
public class RecordController {

  private static final int MAX_CURSOR = 4096;

  private final RecordHistoryQuery records;
  private final UsageEventStore usage;
  private final Clock clock;

  /** 컨트롤러를 만든다. */
  public RecordController(RecordHistoryQuery records, UsageEventStore usage, Clock clock) {
    this.usage = usage;
    this.records = records;
    this.clock = clock;
  }

  /** 회원 본인의 기록 한 쪽. */
  @GetMapping("/me/lotto/records")
  public ResponseEntity<Page> member(
      Authentication auth,
      @RequestParam(name = "round", required = false) Integer round,
      @RequestParam(name = "purchased", required = false) Boolean purchased,
      @RequestParam(name = "rank", required = false) String rank,
      @RequestParam(name = "cursor", required = false) String cursor) {
    return page(RequestSubject.member(auth), round, purchased, rank, cursor);
  }

  /** 비회원 본인의 기록 한 쪽. 회원 세션이면 401이다(회원 자료는 회원 API로 본다). */
  @GetMapping("/guest/lotto/records")
  public ResponseEntity<Page> guest(
      Authentication auth,
      @RequestParam(name = "round", required = false) Integer round,
      @RequestParam(name = "purchased", required = false) Boolean purchased,
      @RequestParam(name = "rank", required = false) String rank,
      @RequestParam(name = "cursor", required = false) String cursor) {
    RequestSubject subject = RequestSubject.any(auth);
    if (subject.member()) {
      throw new BusinessException(ErrorCode.UNAUTHENTICATED, "비회원 자격이 필요합니다.");
    }
    return page(subject, round, purchased, rank, cursor);
  }

  private ResponseEntity<Page> page(
      RequestSubject subject, Integer round, Boolean purchased, String rank, String cursor) {
    if ((round != null && round < 1) || (cursor != null && cursor.length() > MAX_CURSOR)) {
      throw new BusinessException(ErrorCode.VALIDATION_FAILED, "조회 조건을 확인해 주세요.");
    }
    RecordPage page = records.list(subject.subjectId(), round, purchased, rank, cursor);
    usage.recordOncePerDay(subject.subjectId(), UsageEventStore.RECORDS_VIEWED, clock.instant());
    List<Item> items = page.items().stream().map(RecordController::item).toList();
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(
            new Page(
                page.selectedRound(),
                items,
                page.nextCursor(),
                page.emptyReason() == null ? null : page.emptyReason().name(),
                clock.instant()));
  }

  private static Item item(RecordView view) {
    RecordEvaluation e = view.evaluation();
    Evaluation evaluation =
        e == null
            ? new Evaluation("NOT_PUBLISHED", null, null, null)
            : new Evaluation(
                e.state(),
                e.resultRevision() == null ? null : e.resultRevision().toString(),
                e.rank(),
                e.prizePerGame() == null ? null : e.prizePerGame().toString());
    return new Item(
        view.recordId(),
        view.roundNo(),
        view.numbers().toList(),
        view.recommended(),
        view.purchased(),
        Long.toString(view.version()),
        view.expiresAt(),
        evaluation);
  }

  /**
   * 기록 한 쪽(openapi {@code RecordPage}).
   *
   * @param selectedRound 조회한 회차
   * @param items 기록
   * @param nextCursor 다음 쪽 cursor
   * @param emptyReason 0건 이유
   * @param serverTime 서버 시각
   */
  public record Page(
      Integer selectedRound,
      List<Item> items,
      String nextCursor,
      String emptyReason,
      Instant serverTime) {}

  /**
   * 기록 하나(openapi {@code Record}).
   *
   * @param recordId 기록 ID
   * @param round 회차
   * @param numbers 번호
   * @param recommended 추천 표시
   * @param purchased 구매 등록
   * @param version 기록 버전(십진 문자열)
   * @param expiresAt 보관 기한
   * @param evaluation 판정
   */
  public record Item(
      UUID recordId,
      int round,
      List<Integer> numbers,
      boolean recommended,
      boolean purchased,
      String version,
      Instant expiresAt,
      Evaluation evaluation) {}

  /**
   * 판정(openapi {@code Evaluation}).
   *
   * @param state 판정 상태
   * @param resultRevision 게시본 개정 번호(십진 문자열)
   * @param rank 등수. 낙첨이면 {@code null}
   * @param prizePerGame 1게임 금액(원, 십진 문자열). 모르면 {@code null}
   */
  public record Evaluation(
      String state, String resultRevision, Integer rank, String prizePerGame) {}
}
