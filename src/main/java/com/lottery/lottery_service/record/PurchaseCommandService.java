package com.lottery.lottery_service.record;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lottery.lottery_service.common.command.CommandOperationStore;
import com.lottery.lottery_service.common.command.CommandResultLoader;
import com.lottery.lottery_service.common.command.CommandStart;
import com.lottery.lottery_service.common.command.CommandToken;
import com.lottery.lottery_service.common.command.CommandTokenCodec;
import com.lottery.lottery_service.common.db.DbValues;
import com.lottery.lottery_service.common.error.BusinessException;
import com.lottery.lottery_service.common.error.ErrorCode;
import com.lottery.lottery_service.common.number.LottoNumbers;
import com.lottery.lottery_service.recommendation.CommandInProgressException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 구매 등록 명령을 멱등하게 처리한다(06 1.1절, D-057). 처음 처리할 때 항목별 결과를 번호 없는 요약으로 멱등 기록에 남기고, 같은 토큰·키·본문이 다시 오면
 * 기록을 다시 보지 않고 그 요약으로 처음 결과를 그대로 돌려준다.
 */
@Component
public class PurchaseCommandService implements CommandResultLoader {

  /** 구매 등록 명령 종류. 명령 토큰에 묶인다. */
  public static final String OPERATION = "REGISTER_PURCHASE";

  /** 완료 코드. */
  public static final String REGISTERED = "PURCHASE_REGISTERED";

  private static final int MAX_CLIENT_ITEM_ID = 64;
  private static final Set<ErrorCode> FINAL_FAILURES =
      EnumSet.of(ErrorCode.FORBIDDEN, ErrorCode.NOT_FOUND, ErrorCode.VALIDATION_FAILED);

  private final CommandTokenCodec tokens;
  private final CommandOperationStore operations;
  private final PurchaseStore purchases;
  private final JdbcTemplate jdbc;
  private final TransactionTemplate tx;
  private final ObjectMapper json = new ObjectMapper();

  /** 서비스를 만든다. */
  public PurchaseCommandService(
      CommandTokenCodec tokens,
      CommandOperationStore operations,
      PurchaseStore purchases,
      JdbcTemplate jdbc,
      PlatformTransactionManager transactionManager) {
    this.tokens = tokens;
    this.operations = operations;
    this.purchases = purchases;
    this.jdbc = jdbc;
    this.tx = new TransactionTemplate(transactionManager);
  }

  /**
   * 구매 등록 명령을 처리한다.
   *
   * @param subjectId 세션에서 구한 회원 주체 ID
   * @param subjectPublicId 그 주체의 외부 ID
   * @param commandToken {@code X-Command-Token}
   * @param idempotencyKey {@code Idempotency-Key}
   * @param roundNo 본문의 회차
   * @param entries 본문의 항목. 1~100개, {@code clientItemId}는 요청 안에서 유일
   * @throws BusinessException 항목 수·{@code clientItemId}가 틀리면 {@code VALIDATION_FAILED}
   * @throws CommandInProgressException 같은 요청이 처리 중일 때
   */
  public PurchaseData register(
      long subjectId,
      UUID subjectPublicId,
      String commandToken,
      UUID idempotencyKey,
      int roundNo,
      List<Entry> entries) {
    validate(entries);
    CommandToken token = tokens.verify(commandToken, subjectPublicId, OPERATION);
    CommandStart start =
        operations.begin(subjectId, token, idempotencyKey, requestHash(roundNo, entries));
    if (start instanceof CommandStart.Completed completed) {
      return replay(completed.publicId());
    }
    if (start instanceof CommandStart.InProgress progress) {
      throw new CommandInProgressException(progress.publicId());
    }
    if (start instanceof CommandStart.Failed failed) {
      throw new BusinessException(ErrorCode.valueOf(failed.completionCode()), "요청을 처리하지 못했습니다.");
    }
    CommandStart.Started started = (CommandStart.Started) start;
    try {
      return tx.execute(
          status -> {
            List<LottoNumbers> sets = new ArrayList<>();
            for (Entry entry : entries) {
              sets.add(entry.numbers());
            }
            PurchaseResult result = purchases.register(subjectId, roundNo, sets);
            Map<String, PurchaseItem> byKey = new HashMap<>();
            for (PurchaseItem item : result.items()) {
              byKey.put(item.numbers().key(), item);
            }
            List<ItemResult> items = new ArrayList<>();
            for (Entry entry : entries) {
              PurchaseItem item = byKey.get(entry.numbers().key());
              items.add(
                  new ItemResult(
                      entry.clientItemId(),
                      item.recordPublicId(),
                      Long.toString(version(item.recordPublicId())),
                      item.outcome()));
            }
            PurchaseData data = new PurchaseData(roundNo, items, result.afterSalesClose());
            operations.succeedWithSummary(started, REGISTERED, write(data));
            return data;
          });
    } catch (BusinessException e) {
      if (FINAL_FAILURES.contains(e.getCode())) {
        operations.markFailed(started, e.getCode().name());
      } else {
        operations.markRetryable(started, e.getCode().name());
      }
      throw e;
    } catch (RuntimeException e) {
      operations.markRetryable(started, ErrorCode.TEMPORARILY_UNAVAILABLE.name());
      throw e;
    }
  }

  @Override
  public String operation() {
    return OPERATION;
  }

  /** 끝난 구매 등록 명령의 처음 결과. */
  @Override
  public PurchaseData load(
      long subjectId, UUID operationPublicId, UUID resourcePublicId, String completionCode) {
    return replay(operationPublicId);
  }

  private PurchaseData replay(UUID operationPublicId) {
    String summary =
        operations
            .summary(operationPublicId)
            .orElseThrow(
                () -> new BusinessException(ErrorCode.RESPONSE_EXPIRED, "처음 결과를 찾을 수 없습니다."));
    try {
      return json.readValue(summary, PurchaseData.class);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("purchase summary is invalid", e);
    }
  }

  private long version(UUID recordPublicId) {
    return jdbc.queryForObject(
        "SELECT version FROM lotto_record WHERE public_id = ?",
        Long.class,
        (Object) DbValues.bytes(recordPublicId));
  }

  private String write(PurchaseData data) {
    try {
      return json.writeValueAsString(data);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }

  private static void validate(List<Entry> entries) {
    if (entries == null || entries.isEmpty() || entries.size() > PurchaseStore.MAX_SETS) {
      throw new BusinessException(ErrorCode.VALIDATION_FAILED, "한 번에 1~100세트까지 등록할 수 있습니다.");
    }
    Set<String> ids = new HashSet<>();
    for (Entry entry : entries) {
      String id = entry.clientItemId();
      if (id == null
          || id.isEmpty()
          || id.length() > MAX_CLIENT_ITEM_ID
          || entry.numbers() == null
          || !ids.add(id)) {
        throw new BusinessException(ErrorCode.VALIDATION_FAILED, "입력 항목을 확인해 주세요.");
      }
    }
  }

  static byte[] requestHash(int roundNo, List<Entry> entries) {
    StringBuilder text = new StringBuilder().append(roundNo);
    for (Entry entry : entries) {
      text.append('|').append(entry.clientItemId()).append('=').append(entry.numbers().key());
    }
    try {
      return MessageDigest.getInstance("SHA-256")
          .digest(text.toString().getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * 입력 항목(openapi {@code PurchaseEntry}).
   *
   * @param clientItemId 화면이 붙인 항목 ID
   * @param numbers 번호 조합
   */
  public record Entry(String clientItemId, LottoNumbers numbers) {}

  /**
   * 항목별 결과(openapi {@code PurchaseItemResult}). 번호는 담지 않는다.
   *
   * @param clientItemId 입력 항목 ID
   * @param recordId 기록 외부 ID
   * @param version 기록 버전(십진 문자열)
   * @param outcome 결과
   */
  public record ItemResult(
      String clientItemId, UUID recordId, String version, PurchaseOutcome outcome) {}

  /**
   * 구매 등록 결과(openapi {@code PurchaseData}).
   *
   * @param round 회차
   * @param items 입력 순서의 항목별 결과
   * @param lateRegistration 판매 마감 뒤 등록이면 {@code true}
   */
  public record PurchaseData(int round, List<ItemResult> items, boolean lateRegistration) {}
}
