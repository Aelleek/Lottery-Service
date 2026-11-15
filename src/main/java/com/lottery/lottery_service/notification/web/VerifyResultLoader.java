package com.lottery.lottery_service.notification.web;

import com.lottery.lottery_service.common.command.CommandResultLoader;
import com.lottery.lottery_service.notification.verify.VerifyCommandService;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 끝난 번호 확인 명령의 결과(openapi {@code VerifiedData}, 지금 연락처 버전)를 명령 조회에 준다. */
@Component
@ConditionalOnProperty(prefix = "lottery.api-v1", name = "enabled", havingValue = "true")
public class VerifyResultLoader implements CommandResultLoader {

  private final JdbcTemplate jdbc;

  /** 로더를 만든다. */
  public VerifyResultLoader(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public String operation() {
    return VerifyCommandService.OPERATION;
  }

  @Override
  public Object load(
      long subjectId, UUID operationPublicId, UUID resourcePublicId, String completionCode) {
    List<Long> versions =
        jdbc.queryForList(
            "SELECT c.version FROM member_contact c JOIN actor_subject s ON s.member_id = c.member_id"
                + " WHERE s.id = ?",
            Long.class,
            subjectId);
    return new VerificationController.Data(Long.toString(versions.isEmpty() ? 0 : versions.get(0)));
  }
}
