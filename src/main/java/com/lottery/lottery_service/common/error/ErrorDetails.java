package com.lottery.lottery_service.common.error;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** 오류 응답의 {@code details}. 입력 위치나 재시도 시각처럼 안전한 정보만 담고, 없는 항목은 응답에서 뺀다. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorDetails(
    @JsonFormat(shape = JsonFormat.Shape.STRING) Instant retryAt,
    String currentVersion,
    List<FieldErrorDetail> fieldErrors) {

  private static final ErrorDetails EMPTY = new ErrorDetails(null, null, null);

  /** 담을 정보가 없는 details({@code {}}). */
  public static ErrorDetails empty() {
    return EMPTY;
  }

  /** 다시 시도할 시각. */
  public static ErrorDetails retryAt(Instant retryAt) {
    return new ErrorDetails(Objects.requireNonNull(retryAt, "retryAt"), null, null);
  }

  /** 현재 버전. 정수 정밀도를 잃지 않게 십진 문자열로 보낸다. */
  public static ErrorDetails currentVersion(long version) {
    return new ErrorDetails(null, Long.toString(version), null);
  }

  /** 입력 오류 목록. */
  public static ErrorDetails fieldErrors(List<FieldErrorDetail> fieldErrors) {
    return new ErrorDetails(null, null, List.copyOf(fieldErrors));
  }
}
