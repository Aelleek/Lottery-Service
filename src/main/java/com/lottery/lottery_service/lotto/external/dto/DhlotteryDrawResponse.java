package com.lottery.lottery_service.lotto.external.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.ToString;

/**
 * 동행복권 회차별 추첨 결과 API 응답 DTO.
 *
 * <p>예시 응답: { "totSellamnt":118628811000,"returnValue":"success","drwNoDate":"2022-01-29",
 * "firstWinamnt":1246819620,"drwtNo6":42,"drwtNo4":22,"firstPrzwnerCo":22,
 * "drwtNo5":32,"bnusNo":39,"firstAccumamnt":27430031640,"drwNo":1000,
 * "drwtNo2":8,"drwtNo3":19,"drwtNo1":2 }
 *
 * <p>주의: - 필드명은 API 스펙 그대로 사용하며, 엔티티 저장 시 가공/검증은 서비스 단계에서 수행. - returnValue는 저장하지 않지만, 성공/미발표 판별을
 * 위해 여기서는 유지.
 */
@Getter
@ToString
@JsonIgnoreProperties(ignoreUnknown = true)
public class DhlotteryDrawResponse {

  /** 해당 회차의 총 판매 금액 */
  @JsonProperty("totSellamnt")
  private Long totSellamnt;

  /** 호출 결과 상태 ("success" | "fail") */
  @JsonProperty("returnValue")
  private String returnValue;

  /** 추첨일 (yyyy-MM-dd) */
  @JsonProperty("drwNoDate")
  private String drwNoDate;

  /** 1등 당첨금액 (1인당) */
  @JsonProperty("firstWinamnt")
  private Long firstWinamnt;

  /** 당첨 번호 1~6 */
  @JsonProperty("drwtNo1")
  private Integer drwtNo1;

  @JsonProperty("drwtNo2")
  private Integer drwtNo2;

  @JsonProperty("drwtNo3")
  private Integer drwtNo3;

  @JsonProperty("drwtNo4")
  private Integer drwtNo4;

  @JsonProperty("drwtNo5")
  private Integer drwtNo5;

  @JsonProperty("drwtNo6")
  private Integer drwtNo6;

  /** 보너스 번호 */
  @JsonProperty("bnusNo")
  private Integer bnusNo;

  /** 1등 당첨자 수 */
  @JsonProperty("firstPrzwnerCo")
  private Integer firstPrzwnerCo;

  /** 1등 당첨금 총액(모든 1등 당첨자 합산 금액) */
  @JsonProperty("firstAccumamnt")
  private Long firstAccumamnt;

  /** 로또 회차 번호 (= round) */
  @JsonProperty("drwNo")
  private Integer drwNo;
}
