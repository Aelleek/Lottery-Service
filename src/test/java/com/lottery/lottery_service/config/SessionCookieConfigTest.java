package com.lottery.lottery_service.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.session.autoconfigure.DefaultCookieSerializerCustomizer;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.DefaultCookieSerializer;

class SessionCookieConfigTest {

  @Test
  @DisplayName("세션 쿠키는 server.servlet.session.cookie 설정대로 이름·Secure·HttpOnly·SameSite를 붙인다")
  void appliesServerCookieProperties() {
    MockEnvironment env =
        new MockEnvironment()
            .withProperty("server.servlet.session.cookie.name", "__Host-lottery_session")
            .withProperty("server.servlet.session.cookie.http-only", "true")
            .withProperty("server.servlet.session.cookie.secure", "true")
            .withProperty("server.servlet.session.cookie.same-site", "Lax");

    String header = write(serializer(env));

    assertThat(header)
        .startsWith("__Host-lottery_session=")
        .contains("; Secure", "; HttpOnly", "; SameSite=Lax", "; Path=/");
  }

  @Test
  @DisplayName("local처럼 secure를 끄면 Secure 없이 내보낸다")
  void localWithoutSecure() {
    MockEnvironment env =
        new MockEnvironment()
            .withProperty("server.servlet.session.cookie.name", "lottery_session")
            .withProperty("server.servlet.session.cookie.secure", "false");

    assertThat(write(serializer(env))).startsWith("lottery_session=").doesNotContain("Secure");
  }

  private static DefaultCookieSerializer serializer(MockEnvironment env) {
    return new SessionCookieConfig()
        .cookieSerializer(
            env,
            new DefaultListableBeanFactory()
                .getBeanProvider(DefaultCookieSerializerCustomizer.class));
  }

  private static String write(DefaultCookieSerializer serializer) {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setSecure(true);
    MockHttpServletResponse response = new MockHttpServletResponse();
    serializer.writeCookieValue(new CookieSerializer.CookieValue(request, response, "abc"));
    return response.getHeader("Set-Cookie");
  }
}
