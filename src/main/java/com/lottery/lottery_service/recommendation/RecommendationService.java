package com.lottery.lottery_service.recommendation;

import com.lottery.lottery_service.common.error.BusinessException;
import com.lottery.lottery_service.common.error.ErrorCode;
import com.lottery.lottery_service.common.number.LottoNumbers;
import com.lottery.lottery_service.recommendation.filter.FilterContext;
import com.lottery.lottery_service.recommendation.filter.GenerationFilterPipeline;
import com.lottery.lottery_service.recommendation.generator.CandidateFilter;
import com.lottery.lottery_service.recommendation.generator.GenerationOutcome;
import com.lottery.lottery_service.recommendation.generator.RecommendationGenerator;
import com.lottery.lottery_service.round.LockedRound;
import com.lottery.lottery_service.round.RoundStore;
import com.lottery.lottery_service.round.domain.RoundCalendar;
import com.lottery.lottery_service.stats.UsageEventStore;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 추천 한 번을 처리한다(05 2절 3~9번). 생성 권한을 잡고, 트랜잭션 밖에서 후보를 만든 뒤, 짧은 트랜잭션에서 주체 → 회차 순으로 잠그고 저장한다. 그 사이 이력이
 * 바뀌었으면 다시 만든다. 실패하면 아무것도 저장하지 않는다.
 */
@Component
public class RecommendationService {

  /** 이력 충돌로 다시 만드는 최대 횟수. */
  public static final int MAX_REGENERATIONS = 2;

  /** 추천 원본은 추첨일+30일까지 둔다(02 8절, D-023). */
  static final Duration RESPONSE_RETENTION = Duration.ofDays(30);

  private static final long POLICY_VERSION = 1;

  private final GenerationLeaseStore leases;
  private final RecommendationStore recommendations;
  private final RoundStore rounds;
  private final PublishedFirstPrizes firstPrizes;
  private final GenerationFilterPipeline filters;
  private final RecommendationGenerator generator;
  private final UsageEventStore usage;
  private final TransactionTemplate tx;

  /** 서비스를 만든다. */
  public RecommendationService(
      GenerationLeaseStore leases,
      RecommendationStore recommendations,
      RoundStore rounds,
      PublishedFirstPrizes firstPrizes,
      GenerationFilterPipeline filters,
      RecommendationGenerator generator,
      UsageEventStore usage,
      PlatformTransactionManager transactionManager) {
    this.leases = leases;
    this.recommendations = recommendations;
    this.rounds = rounds;
    this.firstPrizes = firstPrizes;
    this.filters = filters;
    this.generator = generator;
    this.usage = usage;
    this.tx = new TransactionTemplate(transactionManager);
  }

  /**
   * 5세트를 추천하고 저장한다.
   *
   * @throws BusinessException 생성 중·간격 전이면 {@code GENERATION_BUSY}, 후보가 5개 미만이면 {@code
   *     CANDIDATES_EXHAUSTED}, 시간 안에 못 만들면 {@code GENERATION_TIMEOUT}, 마감이 지났거나 일정이 바뀌었으면 {@code
   *     ROUND_CHANGED}
   */
  public SavedRecommendation recommend(RecommendCommand command) {
    return recommend(command, saved -> {});
  }

  /** 5세트를 추천하고 저장한다. {@code inTransaction}은 저장과 같은 트랜잭션에서 불린다. 멱등 기록 완료처럼 저장과 함께 커밋해야 하는 일에 쓴다. */
  public SavedRecommendation recommend(
      RecommendCommand command, Consumer<SavedRecommendation> inTransaction) {
    if (!command.condition().isDefault()
        && command.condition().candidateCount() < RecommendationGenerator.SETS) {
      throw new BusinessException(ErrorCode.CANDIDATES_EXHAUSTED, "조건에 맞는 새 조합이 5개보다 적습니다.");
    }
    GenerationLease lease = leases.acquire(command.subjectId());
    try {
      CandidateFilter filter =
          command.condition().isDefault()
              ? filters.forContext(
                  new FilterContext(command.roundNo(), firstPrizes.before(command.roundNo())))
              : CandidateFilter.NONE;
      for (int attempt = 0; ; attempt++) {
        Set<LottoNumbers> seen = recommendations.seen(command.subjectId(), command.roundNo());
        List<LottoNumbers> sets = generate(command, filter, seen);
        try {
          return tx.execute(
              status -> {
                SavedRecommendation saved = saveLocked(command, lease, sets);
                inTransaction.accept(saved);
                return saved;
              });
        } catch (RecommendationConflictException e) {
          if (attempt >= MAX_REGENERATIONS) {
            throw new BusinessException(ErrorCode.GENERATION_TIMEOUT, "잠시 뒤 다시 시도해 주세요.");
          }
        }
      }
    } finally {
      leases.finish(lease);
    }
  }

  private List<LottoNumbers> generate(
      RecommendCommand command, CandidateFilter filter, Set<LottoNumbers> seen) {
    GenerationOutcome outcome = generator.generate(command.condition(), filter, seen);
    if (outcome instanceof GenerationOutcome.Generated generated) {
      return generated.sets();
    }
    if (outcome instanceof GenerationOutcome.Exhausted) {
      throw new BusinessException(ErrorCode.CANDIDATES_EXHAUSTED, "조건에 맞는 새 조합이 5개보다 적습니다.");
    }
    throw new BusinessException(ErrorCode.GENERATION_TIMEOUT, "잠시 뒤 다시 시도해 주세요.");
  }

  private SavedRecommendation saveLocked(
      RecommendCommand command, GenerationLease lease, List<LottoNumbers> sets) {
    leases.requireCurrent(lease);
    LockedRound round = rounds.lockShared(command.roundNo());
    new RoundCalendar(List.of(round.schedule()))
        .requireRecommendationAccepted(command.roundNo(), command.scheduleVersion(), round.dbNow());
    Instant acceptedAt = round.dbNow();
    boolean basic = command.condition().isDefault();
    SavedRecommendation saved =
        recommendations.save(
            new NewRecommendation(
                command.subjectId(),
                command.roundNo(),
                sets,
                acceptedAt,
                command.keyDigest(),
                command.requestHash(),
                POLICY_VERSION,
                basic ? GenerationFilterPipeline.VERSION : null,
                basic ? null : conditionJson(command),
                round.schedule().drawAt().plus(RESPONSE_RETENTION)));
    usage.record(
        command.subjectId(),
        command.roundNo(),
        UsageEventStore.RECOMMENDATION_SUCCEEDED,
        1,
        acceptedAt);
    usage.record(
        command.subjectId(),
        command.roundNo(),
        UsageEventStore.RECOMMENDATION_SETS,
        sets.size(),
        acceptedAt);
    return saved;
  }

  private static String conditionJson(RecommendCommand command) {
    return "{\"include\":"
        + new TreeSet<>(command.condition().include())
        + ",\"exclude\":"
        + new TreeSet<>(command.condition().exclude())
        + "}";
  }
}
