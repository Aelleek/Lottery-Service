package com.lottery.lottery_service.notification.web;

import static com.lottery.lottery_service.common.db.DbValues.instant;

import com.lottery.lottery_service.common.command.CommandResultLoader;
import com.lottery.lottery_service.common.error.BusinessException;
import com.lottery.lottery_service.common.error.ErrorCode;
import com.lottery.lottery_service.notification.verify.VerificationIssueService;
import com.lottery.lottery_service.notification.verify.VerificationStore;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 끝난 인증 코드 발급 명령의 처음 결과(openapi {@code ChallengeData})를 명령 조회에 준다. */
@Component
@ConditionalOnProperty(prefix = "lottery.api-v1", name = "enabled", havingValue = "true")
public class VerificationRequestResultLoader implements CommandResultLoader {

  private final JdbcTemplate jdbc;

  /** 로더를 만든다. */
  public VerificationRequestResultLoader(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public String operation() {
    return VerificationIssueService.OPERATION;
  }

  @Override
  public Object load(
      long subjectId, UUID operationPublicId, UUID resourcePublicId, String completionCode) {
    List<LocalDateTime> expires =
        jdbc.queryForList(
            "SELECT v.expires_at FROM verification_challenge v"
                + " JOIN actor_subject s ON s.member_id = v.member_id"
                + " WHERE v.public_id = UUID_TO_BIN(?) AND s.id = ?",
            LocalDateTime.class,
            resourcePublicId.toString(),
            subjectId);
    if (expires.isEmpty()) {
      throw new BusinessException(ErrorCode.RESPONSE_EXPIRED, "이전 요청의 결과가 남아 있지 않습니다.");
    }
    Instant expiresAt = instant(expires.get(0));
    return new VerificationController.ChallengeData(
        resourcePublicId,
        expiresAt,
        expiresAt.minus(VerificationStore.VALIDITY).plus(VerificationStore.RESEND_GAP));
  }
}
