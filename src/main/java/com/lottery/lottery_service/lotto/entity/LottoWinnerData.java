package com.lottery.lottery_service.lotto.entity;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import lombok.*;

/**
 * 로또 회차별 1등 당첨 정보를 저장하는 엔티티.
 *
 * <p>설계 포인트:
 *
 * <ul>
 *   <li>PK는 회차 {@link #round}
 *   <li>6개 당첨번호는 공백 구분 문자열 {@link #winnerData} 로 저장 (예: "2 8 19 22 32 42")
 *   <li>보너스 번호는 {@link #bnusNo}
 *   <li>returnValue는 저장하지 않음
 * </ul>
 */
@Entity
@Table(
    name = "lotto_winner_data",
    indexes = {@Index(name = "idx_lotto_winner_canonical", columnList = "winnerData")})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LottoWinnerData {

  /** 회차 (PK) */
  @Id
  @Column(nullable = false, unique = true)
  private Integer round;

  /** 6개 당첨번호(공백 구분, 오름차순 정렬 권장): 예 "2 8 19 22 32 42" */
  @Column(nullable = false, length = 30)
  private String winnerData;

  /** 보너스 번호 (1~45) */
  @Column(nullable = false)
  private Integer bnusNo;

  /** 해당 회차의 총 판매 금액 */
  @Column(nullable = false)
  private Long totSellamnt;

  /** 1등 당첨 금액(1인당) */
  @Column(nullable = false)
  private Long firstWinamnt;

  /** 1등 당첨자 수 */
  @Column(nullable = false)
  private Integer firstPrzwnerCo;

  /** 1등 당첨금 총액(모든 1등 당첨자 합산 금액) */
  @Column(nullable = false)
  private Long firstAccumamnt;

  /** 추첨일 (yyyy-MM-dd) */
  private LocalDate drwNoDate;

  /** 당첨번호 6개를 오름차순으로 정렬한 canonical 문자열을 만들어 winnerData에 세팅한다. 입력값이 null이면 NPE를 던진다. */
  public void setWinnerDataFromNumbers(
      Integer n1, Integer n2, Integer n3, Integer n4, Integer n5, Integer n6) {
    List<Integer> list = Arrays.asList(n1, n2, n3, n4, n5, n6);
    if (list.stream().anyMatch(v -> v == null)) {
      throw new NullPointerException("Winner numbers must not be null");
    }
    String canonical = list.stream().sorted().map(String::valueOf).collect(Collectors.joining(" "));
    this.winnerData = canonical;
  }
}
