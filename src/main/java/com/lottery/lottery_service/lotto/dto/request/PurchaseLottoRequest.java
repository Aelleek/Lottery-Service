package com.lottery.lottery_service.lotto.dto.request;

import jakarta.validation.constraints.*;
import java.util.List;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 로또 구매 번호 입력 요청 DTO (수동 입력 또는 QR 기반) */
@Getter
@Setter
@NoArgsConstructor
public class PurchaseLottoRequest {

  /** 회차 정보 (예: "1102") */
  @NotBlank(message = "회차 정보는 필수입니다.")
  private String round;

  /** 로또 번호 문자열 리스트 (예: "3, 8, 14, 22, 33, 41") */
  @NotNull(message = "로또 번호 리스트는 비어 있을 수 없습니다.")
  @Size(min = 1, message = "최소 1개 이상의 번호 세트가 필요합니다.")
  private List<
          @Pattern(
              regexp =
                  "^([1-9]|1[0-9]|2[0-9]|3[0-9]|4[0-5])(,\\s*([1-9]|1[0-9]|2[0-9]|3[0-9]|4[0-5])){5}$",
              message = "각 로또 번호 세트는 1~45 사이의 숫자 6개를 쉼표로 구분해 입력해야 합니다.")
          String>
      numbersList;
}
