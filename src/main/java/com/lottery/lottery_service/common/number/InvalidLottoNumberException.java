package com.lottery.lottery_service.common.number;

import java.util.List;

/** 번호가 범위·개수·중복·형식 규칙을 어겼을 때 던진다. 메시지에는 번호 원문을 넣지 않는다. */
public class InvalidLottoNumberException extends IllegalArgumentException {

  private static final long serialVersionUID = 1L;

  private final transient List<String> paths;

  /** 사유를 담아 예외를 만든다. */
  public InvalidLottoNumberException(String message) {
    this(message, List.of());
  }

  /** 사유와 잘못된 입력 위치(예: {@code entries[3].numbers})를 담아 예외를 만든다. */
  public InvalidLottoNumberException(String message, List<String> paths) {
    super(message);
    this.paths = List.copyOf(paths);
  }

  /** 잘못된 입력 위치. 없으면 빈 목록. */
  public List<String> paths() {
    return paths;
  }
}
