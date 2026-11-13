package com.lottery.lottery_service.admin;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class AdminGrantCommandTest {

  private static final UUID MEMBER = UUID.fromString("0b4f7d4e-8f1c-4c55-9d1e-6f7a2b3c4d5e");

  private final AdminGrants grants = mock(AdminGrants.class);

  @Test
  @DisplayName("동작을 적지 않으면 부여, revoke는 회수, reset-mfa는 2차 인증 초기화를 한다")
  void dispatchesByAction() {
    command(env().withProperty("lottery.admin.grant.role", "operator")).execute();
    command(env().withProperty("lottery.admin.grant.action", "revoke")).execute();
    command(env().withProperty("lottery.admin.grant.action", "RESET-MFA")).execute();

    verify(grants).grantByCommand(MEMBER, AdminRole.OPERATOR, "사유");
    verify(grants).revokeByCommand(MEMBER, "사유");
    verify(grants).resetMfaByCommand(MEMBER, "사유");
  }

  @Test
  @DisplayName("모르는 동작이면 아무것도 바꾸지 않고 실패한다")
  void rejectsUnknownAction() {
    AdminGrantCommand command = command(env().withProperty("lottery.admin.grant.action", "delete"));

    assertThatThrownBy(command::execute).isInstanceOf(IllegalArgumentException.class);
    verifyNoInteractions(grants);
  }

  private AdminGrantCommand command(MockEnvironment env) {
    return new AdminGrantCommand(grants, env, null);
  }

  private static MockEnvironment env() {
    return new MockEnvironment()
        .withProperty("lottery.admin.grant.member", MEMBER.toString())
        .withProperty("lottery.admin.grant.reason", "사유");
  }
}
