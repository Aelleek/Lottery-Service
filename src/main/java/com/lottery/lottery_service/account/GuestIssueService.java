package com.lottery.lottery_service.account;

import com.lottery.lottery_service.common.error.BusinessException;
import com.lottery.lottery_service.common.error.ErrorCode;
import com.lottery.lottery_service.common.error.ErrorDetails;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * 비회원 자격 발급 절차(05 1절, 06 2절 {@code POST /guest-credentials}). 출처 검사는 호출한 쪽에서 먼저 한다. 회원 세션이면 발급하지
 * 않고, 유효한 자격이 이미 있으면 새로 만들지 않고 같은 주체를 돌려주며, 새로 만들 때만 IP별 발급 한도를 센다. 자격 원문은 쿠키로만 내려준다.
 */
@Component
@EnableConfigurationProperties(GuestCookieProperties.class)
public class GuestIssueService {

  private final GuestCredentialStore credentials;
  private final GuestIssueRateLimiter limiter;
  private final Clock clock;
  private final GuestCookieProperties cookieProperties;

  /** 서비스를 만든다. */
  public GuestIssueService(
      GuestCredentialStore credentials,
      GuestIssueRateLimiter limiter,
      Clock clock,
      GuestCookieProperties cookieProperties) {
    this.credentials = credentials;
    this.limiter = limiter;
    this.clock = clock;
    this.cookieProperties = cookieProperties;
  }

  /** 자격 쿠키 이름. */
  public String cookieName() {
    return cookieProperties.name();
  }

  /**
   * 자격을 발급하거나 있는 자격을 확인한다.
   *
   * @param existingToken 요청에 실려 온 자격 쿠키 원문. 없으면 {@code null}
   * @param memberSession 로그인한 회원 세션이 있는지
   * @param clientIp 발급 한도를 셀 IP
   * @throws BusinessException 회원 세션이면 {@code FORBIDDEN}, 발급 한도를 넘으면 {@code RATE_LIMITED}(다시 시도할 시각)
   */
  public Outcome issue(String existingToken, boolean memberSession, String clientIp) {
    if (memberSession) {
      throw new BusinessException(ErrorCode.FORBIDDEN, "로그인한 상태에서는 비회원 자격을 만들지 않습니다.");
    }
    if (existingToken != null) {
      Optional<GuestSubject> current = credentials.resolve(existingToken);
      if (current.isPresent()) {
        return new Outcome.Existing(current.get());
      }
    }
    Optional<Instant> retryAt = limiter.tryAcquire(clientIp);
    if (retryAt.isPresent()) {
      throw new BusinessException(
          ErrorCode.RATE_LIMITED, "잠시 뒤 다시 시도해 주세요.", ErrorDetails.retryAt(retryAt.get()));
    }
    GuestCredential issued = credentials.issue();
    return new Outcome.Issued(issued, cookie(issued));
  }

  /** 자격 쿠키. Secure, HttpOnly, SameSite=Lax, 경로 전체, 자격 만료까지(02 7.1절). */
  public ResponseCookie cookie(GuestCredential credential) {
    long seconds =
        Math.max(0, Duration.between(clock.instant(), credential.expiresAt()).getSeconds());
    return ResponseCookie.from(cookieProperties.name(), credential.token())
        .secure(cookieProperties.secure())
        .httpOnly(true)
        .sameSite("Lax")
        .path("/")
        .maxAge(seconds)
        .build();
  }

  /** 비회원 쿠키를 지우는 쿠키(Max-Age=0). 로그인해 귀속을 시도한 뒤에 쓴다. */
  public ResponseCookie expiredCookie() {
    return ResponseCookie.from(cookieProperties.name(), "")
        .secure(cookieProperties.secure())
        .httpOnly(true)
        .sameSite("Lax")
        .path("/")
        .maxAge(0)
        .build();
  }

  /** 발급 결과. */
  public sealed interface Outcome {

    /**
     * 새 자격을 만들었다. 쿠키를 내려준다.
     *
     * @param credential 새 자격
     * @param cookie 내려줄 쿠키
     */
    record Issued(GuestCredential credential, ResponseCookie cookie) implements Outcome {}

    /**
     * 유효한 자격이 이미 있다. 같은 주체를 쓴다.
     *
     * @param subject 그 자격의 주체
     */
    record Existing(GuestSubject subject) implements Outcome {}
  }
}
