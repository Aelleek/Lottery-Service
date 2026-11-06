package com.lottery.lottery_service.common.number;

/** 번호가 범위·개수·중복·형식 규칙을 어겼을 때 던진다. 메시지에는 번호 원문을 넣지 않는다. */
public class InvalidLottoNumberException extends IllegalArgumentException {

  private static final long serialVersionUID = 1L;

  /** 사유를 담아 예외를 만든다. */
  public InvalidLottoNumberException(String message) {
    super(message);
  }
}
