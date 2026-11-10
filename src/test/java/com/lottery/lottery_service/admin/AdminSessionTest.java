package com.lottery.lottery_service.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;

class AdminSessionTest {

  private static final Instant T0 = Instant.parse("2026-11-10T14:00:00Z");

  @Test
  @DisplayName("관리자 인증 전 세션과 세션 없음은 받지 않는다")
  void notStarted() {
    assertThat(AdminSession.touch(null, T0)).isFalse();
    assertThat(AdminSession.touch(new MockHttpSession(), T0)).isFalse();
  }

  @Test
  @DisplayName("30분 안에 요청이 이어지면 받고, 마지막 요청에서 30분이 되면 끝내고 관리자 표시를 지운다")
  void idleTimeout() {
    MockHttpSession session = new MockHttpSession();
    AdminSession.start(session, T0);

    assertThat(AdminSession.touch(session, T0.plus(Duration.ofMinutes(29)))).isTrue();
    assertThat(AdminSession.touch(session, T0.plus(Duration.ofMinutes(58)))).isTrue();
    assertThat(AdminSession.touch(session, T0.plus(Duration.ofMinutes(88)))).isFalse();
    assertThat(session.getAttribute(AdminSession.AUTHENTICATED_AT)).isNull();
    assertThat(AdminSession.touch(session, T0.plus(Duration.ofMinutes(89)))).isFalse();
  }

  @Test
  @DisplayName("계속 쓰더라도 인증한 지 8시간이 되면 끝낸다")
  void absoluteLimit() {
    MockHttpSession session = new MockHttpSession();
    AdminSession.start(session, T0);
    Instant at = T0;
    while (at.isBefore(T0.plus(AdminSession.MAX_AGE).minus(Duration.ofMinutes(20)))) {
      at = at.plus(Duration.ofMinutes(20));
      assertThat(AdminSession.touch(session, at)).isTrue();
    }

    assertThat(AdminSession.touch(session, T0.plus(AdminSession.MAX_AGE))).isFalse();
  }
}
