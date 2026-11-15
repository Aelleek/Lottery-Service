package com.lottery.lottery_service.notification.web;

import com.lottery.lottery_service.common.command.CommandResultLoader;
import com.lottery.lottery_service.common.error.BusinessException;
import com.lottery.lottery_service.common.error.ErrorCode;
import com.lottery.lottery_service.notification.verify.ContactCipher;
import com.lottery.lottery_service.notification.verify.PreferenceCommandService;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 끝난 알림 설정 변경 명령의 결과(openapi {@code Preference}, 지금 설정)를 명령 조회에 준다. */
@Component
@ConditionalOnProperty(prefix = "lottery.api-v1", name = "enabled", havingValue = "true")
public class PreferenceResultLoader implements CommandResultLoader {

  private final JdbcTemplate jdbc;
  private final ContactCipher cipher;

  /** 로더를 만든다. */
  public PreferenceResultLoader(JdbcTemplate jdbc, ContactCipher cipher) {
    this.jdbc = jdbc;
    this.cipher = cipher;
  }

  @Override
  public String operation() {
    return PreferenceCommandService.OPERATION;
  }

  @Override
  public Object load(
      long subjectId, UUID operationPublicId, UUID resourcePublicId, String completionCode) {
    List<Long> members =
        jdbc.queryForList(
            "SELECT member_id FROM actor_subject WHERE id = ? AND member_id IS NOT NULL",
            Long.class,
            subjectId);
    if (members.isEmpty()) {
      throw new BusinessException(ErrorCode.NOT_FOUND, "요청한 내용을 찾을 수 없습니다.");
    }
    return PreferenceController.load(jdbc, cipher, members.get(0));
  }
}
