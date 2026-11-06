package com.lottery.lottery_service.common.command;

import com.lottery.lottery_service.common.error.BusinessException;
import com.lottery.lottery_service.common.error.ErrorCode;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 명령 토큰 발급(06 2절 {@code POST /command-tokens}). 발급은 업무 실행이 아니고, 허용되지 않은 명령이면 {@code FORBIDDEN}이다.
 * 비회원은 추천만 할 수 있다. 추천 토큰에는 회차와 일정 버전을 묶고, 다른 명령은 묶지 않는다(대상은 실행 때 요청 본문으로 확인한다).
 */
@Component
public class CommandTokenService {

  /** 추천 명령. */
  public static final String RECOMMEND = "RECOMMEND";

  /** 회원이 받을 수 있는 명령(openapi {@code CommandTokenRequest.operation}). */
  public static final Set<String> MEMBER_OPERATIONS =
      Set.of(
          RECOMMEND,
          "REGISTER_PURCHASE",
          "CANCEL_PURCHASE",
          "IMPORT_GUEST",
          "REQUEST_VERIFICATION",
          "VERIFY_CONTACT",
          "CHANGE_PREFERENCE",
          "WITHDRAW");

  /** 비회원이 받을 수 있는 명령. */
  public static final Set<String> GUEST_OPERATIONS = Set.of(RECOMMEND);

  private final CommandTokenCodec codec;

  /** 서비스를 만든다. */
  public CommandTokenService(CommandTokenCodec codec) {
    this.codec = codec;
  }

  /**
   * 토큰을 발급한다.
   *
   * @param subjectPublicId 세션이나 비회원 쿠키에서 구한 주체의 외부 ID
   * @param member 회원 주체인지
   * @param operation 명령 종류
   * @param round 추천 회차. 다른 명령이면 무시한다
   * @param scheduleVersion 추천 일정 버전. 다른 명령이면 무시한다
   * @throws BusinessException 허용되지 않은 명령이면 {@code FORBIDDEN}, 추천인데 회차나 버전이 없으면 {@code
   *     VALIDATION_FAILED}
   */
  public IssuedToken issue(
      UUID subjectPublicId, boolean member, String operation, Integer round, Long scheduleVersion) {
    Set<String> allowed = member ? MEMBER_OPERATIONS : GUEST_OPERATIONS;
    if (operation == null || !allowed.contains(operation)) {
      throw new BusinessException(ErrorCode.FORBIDDEN, "허용되지 않은 요청입니다.");
    }
    String token;
    if (RECOMMEND.equals(operation)) {
      if (round == null || round < 1 || scheduleVersion == null || scheduleVersion < 0) {
        throw new BusinessException(ErrorCode.VALIDATION_FAILED, "회차와 일정 버전이 필요합니다.");
      }
      token = codec.issue(subjectPublicId, operation, round, scheduleVersion);
    } else {
      token = codec.issue(subjectPublicId, operation, null, null);
    }
    return new IssuedToken(token, codec.verify(token, subjectPublicId, operation).expiresAt());
  }

  /**
   * 발급한 토큰(openapi {@code CommandToken}). 토큰은 로그에 남기지 않는다.
   *
   * @param token 서명된 토큰
   * @param expiresAt 만료 시각
   */
  public record IssuedToken(String token, Instant expiresAt) {

    @Override
    public String toString() {
      return "IssuedToken[expiresAt=" + expiresAt + "]";
    }
  }
}
