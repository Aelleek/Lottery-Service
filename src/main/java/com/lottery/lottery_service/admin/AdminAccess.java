package com.lottery.lottery_service.admin;

import com.lottery.lottery_service.common.error.BusinessException;
import com.lottery.lottery_service.common.error.ErrorCode;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 회원의 현재 관리자 역할을 {@code admin_grant}에서 확인한다. 회수된 권한과 활성 상태가 아닌 회원의 권한은 없는 것으로 본다. */
@Component
public class AdminAccess {

  private final JdbcTemplate jdbc;

  /** 확인기를 만든다. */
  public AdminAccess(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** 회원의 현재 관리자 역할. 없으면 빈 값. */
  public Optional<AdminRole> roleOf(long memberId) {
    List<String> roles =
        jdbc.queryForList(
            "SELECT g.role FROM admin_grant g JOIN member m ON m.id = g.member_id"
                + " WHERE g.member_id = ? AND g.revoked_at IS NULL AND m.state = 'ACTIVE'",
            String.class,
            memberId);
    return roles.stream().findFirst().map(AdminRole::valueOf);
  }

  /**
   * 회원이 {@code required} 이상의 역할을 가졌는지 확인한다. 요청마다 현재 권한을 다시 읽는다.
   *
   * @return 회원의 현재 역할
   * @throws BusinessException 역할이 없거나 모자라면 {@code FORBIDDEN}
   */
  public AdminRole require(long memberId, AdminRole required) {
    return roleOf(memberId)
        .filter(role -> role.covers(required))
        .orElseThrow(() -> new BusinessException(ErrorCode.FORBIDDEN, "허용되지 않은 요청입니다."));
  }
}
