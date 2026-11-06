package com.lottery.lottery_service.common.command;

import java.util.UUID;

/** 명령을 시작할 때의 판단. */
public sealed interface CommandStart {

  /**
   * 이 요청이 처리를 맡았다. 업무 트랜잭션에서 {@link CommandOperationStore#succeed}로 끝낸다.
   *
   * @param operationId 멱등 기록 ID
   * @param publicId 멱등 기록 외부 ID
   * @param leaseToken 처리 권한 토큰
   */
  record Started(long operationId, UUID publicId, UUID leaseToken) implements CommandStart {}

  /**
   * 같은 요청이 이미 끝났다. 처음 결과를 돌려준다.
   *
   * @param publicId 멱등 기록 외부 ID
   * @param resourcePublicId 처음 결과 자원의 외부 ID
   * @param completionCode 완료 코드
   */
  record Completed(UUID publicId, UUID resourcePublicId, String completionCode)
      implements CommandStart {}

  /**
   * 같은 요청이 다른 곳에서 처리 중이다. 202와 조회 경로를 준다.
   *
   * @param publicId 멱등 기록 외부 ID
   */
  record InProgress(UUID publicId) implements CommandStart {}

  /**
   * 같은 요청이 확정 실패했다. 오류 응답을 준다.
   *
   * @param publicId 멱등 기록 외부 ID
   * @param completionCode 실패 코드
   */
  record Failed(UUID publicId, String completionCode) implements CommandStart {}
}
