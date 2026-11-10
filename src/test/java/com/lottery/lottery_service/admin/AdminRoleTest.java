package com.lottery.lottery_service.admin;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AdminRoleTest {

  @Test
  @DisplayName("ADMIN은 두 역할을 모두 덮고 OPERATOR는 자기 역할만 덮는다")
  void covers() {
    assertThat(AdminRole.ADMIN.covers(AdminRole.ADMIN)).isTrue();
    assertThat(AdminRole.ADMIN.covers(AdminRole.OPERATOR)).isTrue();
    assertThat(AdminRole.OPERATOR.covers(AdminRole.OPERATOR)).isTrue();
    assertThat(AdminRole.OPERATOR.covers(AdminRole.ADMIN)).isFalse();
  }
}
