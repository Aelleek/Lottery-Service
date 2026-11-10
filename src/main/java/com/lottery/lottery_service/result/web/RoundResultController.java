package com.lottery.lottery_service.result.web;

import com.lottery.lottery_service.common.error.BusinessException;
import com.lottery.lottery_service.common.error.ErrorCode;
import com.lottery.lottery_service.common.error.ErrorDetails;
import com.lottery.lottery_service.common.error.FieldErrorDetail;
import com.lottery.lottery_service.common.number.LottoNumbers;
import com.lottery.lottery_service.result.domain.PrizeRank;
import com.lottery.lottery_service.result.evaluate.InstantCheck;
import com.lottery.lottery_service.result.query.Prize;
import com.lottery.lottery_service.result.query.ResultState;
import com.lottery.lottery_service.result.query.RoundResult;
import com.lottery.lottery_service.result.query.RoundResultQuery;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 인증 없이 쓰는 결과 API(06 2절): {@code GET /api/v1/lotto/rounds/{round}/result}와 {@code POST
 * /api/v1/lotto/check}. 즉시 확인은 입력 번호를 저장하거나 로그에 남기지 않는다. 새 API는 {@code
 * lottery.api-v1.enabled=true}일 때만 연다.
 */
@RestController
@ConditionalOnProperty(prefix = "lottery.api-v1", name = "enabled", havingValue = "true")
public class RoundResultController {

  private final RoundResultQuery results;
  private final InstantCheck instantCheck;

  /** 컨트롤러를 만든다. */
  public RoundResultController(RoundResultQuery results, InstantCheck instantCheck) {
    this.results = results;
    this.instantCheck = instantCheck;
  }

  /** 회차 결과. 등록된 회차가 아니면 404 {@code NOT_FOUND}. */
  @GetMapping("/api/v1/lotto/rounds/{round}/result")
  public DrawResultResponse result(@PathVariable("round") int round) {
    requireRound(round);
    RoundResult result =
        results
            .find(round)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "회차를 찾을 수 없습니다."));
    return DrawResultResponse.of(result);
  }

  /** 저장하지 않는 즉시 확인. 등록된 회차가 아니면 400 {@code VALIDATION_FAILED}. */
  @PostMapping("/api/v1/lotto/check")
  public CheckResponse check(@RequestBody CheckRequest request) {
    if (request.round() == null) {
      throw invalid("round", "회차가 필요합니다.");
    }
    requireRound(request.round());
    if (request.sets() == null) {
      throw invalid("sets", "번호가 필요합니다.");
    }
    List<LottoNumbers> sets = request.sets().stream().map(LottoNumbers::of).toList();
    InstantCheck.Checked checked =
        instantCheck
            .check(request.round(), sets)
            .orElseThrow(() -> invalid("round", "확인할 수 없는 회차입니다."));
    RoundResult result = checked.result();
    List<CheckItem> items = new ArrayList<>(sets.size());
    for (int i = 0; i < sets.size(); i++) {
      Evaluation evaluation =
          checked.ranks().isEmpty()
              ? new Evaluation(result.state().name(), null, null, null)
              : Evaluation.evaluated(result, checked.ranks().get(i));
      items.add(new CheckItem(sets.get(i).toList(), evaluation));
    }
    return new CheckResponse(request.round(), items);
  }

  private static void requireRound(int round) {
    if (round < 1) {
      throw invalid("round", "회차는 1 이상이어야 합니다.");
    }
  }

  private static BusinessException invalid(String path, String reason) {
    return new BusinessException(
        ErrorCode.VALIDATION_FAILED,
        reason,
        ErrorDetails.fieldErrors(List.of(new FieldErrorDetail(path, reason))));
  }

  private static String decimal(Long value) {
    return value == null ? null : Long.toString(value);
  }

  /**
   * openapi {@code DrawResult}.
   *
   * @param round 회차
   * @param state PUBLISHED, NOT_PUBLISHED, DELAYED
   * @param publishedRevision 게시 버전(십진 문자열). 게시 전이면 {@code null}
   * @param numbers 당첨 번호(오름차순). 게시 전이면 {@code null}
   * @param bonus 보너스 번호. 게시 전이면 {@code null}
   * @param prizes 등수별 금액
   * @param completeness NONE, NUMBERS_ONLY, PARTIAL_PRIZES, COMPLETE
   * @param sourceCheckedAt 원천 확인 시각
   * @param source 게시본 출처(API, MANUAL). 게시 전이면 {@code null}
   * @param apiConfirmedAt 직접 등록한 결과를 결과 API로 확인한 시각. MANUAL인데 {@code null}이면 공식 결과 확인 전이다(D-061)
   */
  public record DrawResultResponse(
      int round,
      String state,
      String publishedRevision,
      List<Integer> numbers,
      Integer bonus,
      List<PrizeResponse> prizes,
      String completeness,
      Instant sourceCheckedAt,
      String source,
      Instant apiConfirmedAt) {

    static DrawResultResponse of(RoundResult result) {
      boolean published = result.state() == ResultState.PUBLISHED;
      return new DrawResultResponse(
          result.round(),
          result.state().name(),
          decimal(result.publicationVersion()),
          published ? result.winning().main().toList() : null,
          published ? result.winning().bonus().value() : null,
          result.prizes().stream().map(PrizeResponse::of).toList(),
          result.completeness(),
          result.sourceCheckedAt(),
          result.source(),
          result.apiConfirmedAt());
    }
  }

  /**
   * openapi {@code Prize}.
   *
   * @param rank 등수
   * @param prizePerGame 1게임 금액(십진 문자열). 모르면 {@code null}
   * @param winnerCount 당첨 수(십진 문자열). 모르면 {@code null}
   */
  public record PrizeResponse(int rank, String prizePerGame, String winnerCount) {
    static PrizeResponse of(Prize prize) {
      return new PrizeResponse(
          prize.rank(), decimal(prize.prizePerGame()), decimal(prize.winnerCount()));
    }
  }

  /**
   * openapi {@code CheckInput}.
   *
   * @param round 회차
   * @param sets 번호 1~5세트
   */
  public record CheckRequest(Integer round, List<List<Integer>> sets) {}

  /**
   * openapi {@code CheckResult}.
   *
   * @param round 회차
   * @param items 입력 순서대로 결과
   */
  public record CheckResponse(int round, List<CheckItem> items) {}

  /**
   * 즉시 확인 한 세트.
   *
   * @param numbers 입력 번호(오름차순)
   * @param evaluation 판정
   */
  public record CheckItem(List<Integer> numbers, Evaluation evaluation) {}

  /**
   * openapi {@code Evaluation}. EVALUATED에서 {@code rank=null}이면 낙첨이다.
   *
   * @param state NOT_PUBLISHED, DELAYED, EVALUATED
   * @param resultRevision 판정에 쓴 게시 버전(십진 문자열)
   * @param rank 등수. 낙첨이나 모름이면 {@code null}
   * @param prizePerGame 1게임 금액(십진 문자열). 모르면 {@code null}
   */
  public record Evaluation(String state, String resultRevision, Integer rank, String prizePerGame) {

    static Evaluation evaluated(RoundResult result, PrizeRank rank) {
      return new Evaluation(
          "EVALUATED",
          decimal(result.publicationVersion()),
          rank.isWinning() ? rank.ordinal() + 1 : null,
          decimal(result.prizePerGame(rank)));
    }
  }
}
