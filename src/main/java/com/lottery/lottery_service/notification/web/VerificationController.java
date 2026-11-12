package com.lottery.lottery_service.notification.web;

import com.lottery.lottery_service.auth.MemberPrincipal;
import com.lottery.lottery_service.common.error.BusinessException;
import com.lottery.lottery_service.common.error.ErrorCode;
import com.lottery.lottery_service.common.web.CanonicalUuid;
import com.lottery.lottery_service.common.web.RequestSubject;
import com.lottery.lottery_service.notification.verify.PhoneMask;
import com.lottery.lottery_service.notification.verify.VerificationIssueService;
import com.lottery.lottery_service.notification.verify.VerifyCommandService;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 인증 코드 발급({@code POST /me/notification-verifications})과 확인({@code POST
 * /me/notification-verifications/{challengeId}/verify}). 회원은 세션에서만 정하고, 다른 회원의 요청 ID는 만료와 같게 답한다.
 */
@RestController
@RequestMapping("/api/v1")
@ConditionalOnProperty(prefix = "lottery.api-v1", name = "enabled", havingValue = "true")
public class VerificationController {

  private static final Pattern PHONE = Pattern.compile("\\+[1-9][0-9]{7,14}");

  private final VerifyCommandService verifications;
  private final VerificationIssueService issues;
  private final Clock clock;

  /** 컨트롤러를 만든다. */
  public VerificationController(
      VerifyCommandService verifications, VerificationIssueService issues, Clock clock) {
    this.verifications = verifications;
    this.issues = issues;
    this.clock = clock;
  }

  /** 인증 코드를 발급해 보낸다. 성공은 발급과 제공자 접수(또는 접수 확인 중)이며 전달을 보장하지 않는다. */
  @PostMapping("/me/notification-verifications")
  public ResponseEntity<Requested> request(
      Authentication auth,
      @RequestHeader("X-Command-Token") String commandToken,
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @RequestBody RequestInput input) {
    if (auth == null || !(auth.getPrincipal() instanceof MemberPrincipal member)) {
      throw new BusinessException(ErrorCode.UNAUTHENTICATED, "로그인이 필요합니다.");
    }
    RequestSubject subject = RequestSubject.member(auth);
    UUID key;
    try {
      key = CanonicalUuid.parse(idempotencyKey);
    } catch (IllegalArgumentException e) {
      throw new BusinessException(ErrorCode.VALIDATION_FAILED, "입력 형식을 확인해 주세요.");
    }
    long expectedVersion;
    try {
      expectedVersion =
          input.expectedContactVersion() == null
              ? -1
              : Long.parseLong(input.expectedContactVersion());
    } catch (NumberFormatException e) {
      expectedVersion = -1;
    }
    if (input.phoneNumber() == null
        || !PHONE.matcher(input.phoneNumber()).matches()
        || expectedVersion < 0
        || commandToken.length() > 4096) {
      throw new BusinessException(ErrorCode.VALIDATION_FAILED, "입력을 확인해 주세요.");
    }
    VerificationIssueService.Requested requested =
        issues.request(
            member.memberId(),
            subject.subjectId(),
            subject.subjectPublicId(),
            commandToken,
            key,
            input.phoneNumber(),
            expectedVersion);
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(
            new Requested(
                requested.operationId(),
                "SUCCEEDED",
                new ChallengeData(
                    requested.challengeId(), requested.expiresAt(), requested.resendAt()),
                clock.instant()));
  }

  /** 코드를 확인한다. */
  @PostMapping("/me/notification-verifications/{challengeId}/verify")
  public ResponseEntity<Success> verify(
      Authentication auth,
      @PathVariable("challengeId") String challengeId,
      @RequestHeader("X-Command-Token") String commandToken,
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @RequestBody Input input) {
    if (auth == null || !(auth.getPrincipal() instanceof MemberPrincipal member)) {
      throw new BusinessException(ErrorCode.UNAUTHENTICATED, "로그인이 필요합니다.");
    }
    RequestSubject subject = RequestSubject.member(auth);
    UUID challenge;
    try {
      challenge = CanonicalUuid.parse(challengeId);
    } catch (IllegalArgumentException e) {
      throw new BusinessException(ErrorCode.NOT_FOUND, "요청한 내용을 찾을 수 없습니다.");
    }
    UUID key;
    try {
      key = CanonicalUuid.parse(idempotencyKey);
    } catch (IllegalArgumentException e) {
      throw new BusinessException(ErrorCode.VALIDATION_FAILED, "입력 형식을 확인해 주세요.");
    }
    if (input.code() == null || !input.code().matches("[0-9]{6}") || commandToken.length() > 4096) {
      throw new BusinessException(ErrorCode.VALIDATION_FAILED, "입력을 확인해 주세요.");
    }
    VerifyCommandService.Verified verified =
        verifications.verify(
            member.memberId(),
            subject.subjectId(),
            subject.subjectPublicId(),
            commandToken,
            key,
            challenge,
            input.code());
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(
            new Success(
                verified.operationId(),
                "SUCCEEDED",
                new Data(Long.toString(verified.contactVersion())),
                clock.instant()));
  }

  /**
   * 발급 요청 본문(openapi {@code VerificationInput}).
   *
   * @param phoneNumber 국제 형식 번호
   * @param expectedContactVersion 화면이 본 연락처 버전(십진 문자열)
   */
  public record RequestInput(String phoneNumber, String expectedContactVersion) {

    /** 번호가 로그에 남지 않게 가린다. */
    @Override
    public String toString() {
      return "RequestInput[phoneNumber=" + PhoneMask.mask(phoneNumber) + "]";
    }
  }

  /**
   * 발급 응답(openapi {@code VerificationRequested}).
   *
   * @param operationId 명령 ID
   * @param status SUCCEEDED
   * @param data 인증 요청
   * @param serverTime 서버 시각
   */
  public record Requested(
      UUID operationId, String status, ChallengeData data, Instant serverTime) {}

  /**
   * 인증 요청(openapi {@code ChallengeData}).
   *
   * @param challengeId 인증 요청 ID
   * @param expiresAt 코드 만료 시각
   * @param resendAt 다시 요청할 수 있는 가장 이른 시각
   */
  public record ChallengeData(UUID challengeId, Instant expiresAt, Instant resendAt) {}

  /**
   * 요청 본문(openapi {@code VerifyInput}).
   *
   * @param code 여섯 자리 코드
   */
  public record Input(String code) {

    /** 코드가 로그에 남지 않게 가린다. */
    @Override
    public String toString() {
      return "Input[code=******]";
    }
  }

  /**
   * 응답(openapi {@code VerificationSuccess}).
   *
   * @param operationId 명령 ID
   * @param status SUCCEEDED
   * @param data 인증된 연락처 버전
   * @param serverTime 서버 시각
   */
  public record Success(UUID operationId, String status, Data data, Instant serverTime) {}

  /**
   * 인증 결과(openapi {@code VerifiedData}).
   *
   * @param verifiedContactVersion 인증된 연락처 버전(십진 문자열)
   */
  public record Data(String verifiedContactVersion) {}
}
