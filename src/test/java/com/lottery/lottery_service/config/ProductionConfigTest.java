package com.lottery.lottery_service.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;

/** 제품 설정 파일의 보안 관련 값을 확인한다. 테스트 실행 때는 테스트 설정이 제품 설정을 가리므로 파일을 직접 읽는다. */
class ProductionConfigTest {

  @Test
  @DisplayName("기본 설정은 Flyway를 켜고, 세션 쿠키는 __Host 이름에 Secure·HttpOnly·SameSite=Lax다")
  void defaultsAreSecure() throws IOException {
    PropertySource<?> main = load("src/main/resources/application.yml");

    assertThat(main.getProperty("spring.flyway.enabled")).isEqualTo(true);
    assertThat(main.getProperty("server.servlet.session.cookie.name"))
        .isEqualTo("__Host-lottery_session");
    assertThat(main.getProperty("server.servlet.session.cookie.secure")).isEqualTo(true);
    assertThat(main.getProperty("server.servlet.session.cookie.http-only")).isEqualTo(true);
    assertThat(main.getProperty("server.servlet.session.cookie.same-site")).isEqualTo("Lax");
    assertThat(main.getProperty("spring.h2.console.enabled")).isNull();
  }

  @Test
  @DisplayName("전달 헤더는 설정한 앞단에서 온 것만 믿고, 기본값은 아무 앞단도 믿지 않는다(07 5절 5번)")
  void forwardedHeadersTrustOnlyConfiguredProxies() throws IOException {
    PropertySource<?> main = load("src/main/resources/application.yml");

    assertThat(main.getProperty("server.forward-headers-strategy")).isEqualTo("native");
    assertThat(main.getProperty("server.tomcat.remoteip.internal-proxies"))
        .isEqualTo("${LOTTERY_TRUSTED_PROXIES:}");
  }

  @Test
  @DisplayName("Secure를 끄는 것은 local 프로필뿐이고 접두어 없는 쿠키 이름을 쓴다")
  void onlyLocalDisablesSecure() throws IOException {
    PropertySource<?> local = load("src/main/resources/application-local.yml");

    assertThat(local.getProperty("server.servlet.session.cookie.secure")).isEqualTo(false);
    assertThat(local.getProperty("server.servlet.session.cookie.name"))
        .isEqualTo("lottery_session");
    assertThat(local.getProperty("lottery.guest-cookie.secure")).isEqualTo(false);
  }

  private static PropertySource<?> load(String path) throws IOException {
    List<PropertySource<?>> sources =
        new YamlPropertySourceLoader().load(path, new FileSystemResource(path));
    return sources.get(0);
  }
}
