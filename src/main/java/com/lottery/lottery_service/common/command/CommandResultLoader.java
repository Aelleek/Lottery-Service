package com.lottery.lottery_service.common.command;

import java.util.UUID;

/** 끝난 명령의 처음 결과를 다시 읽는다. 명령 종류마다 하나씩 둔다({@link OperationQuery}). */
public interface CommandResultLoader {

  /** 이 로더가 맡는 명령 종류. 명령 토큰의 명령 이름과 같다. */
  String operation();

  /**
   * 처음 결과를 읽는다.
   *
   * @param subjectId 요청한 주체 ID. 결과 자원의 현재 소유권 확인에 쓴다
   * @param operationPublicId 멱등 기록 외부 ID
   * @param resourcePublicId 처음 결과 자원의 외부 ID
   * @param completionCode 완료 코드
   * @return 응답 {@code data}에 넣을 값
   */
  Object load(long subjectId, UUID operationPublicId, UUID resourcePublicId, String completionCode);
}
