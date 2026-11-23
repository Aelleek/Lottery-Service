package com.lottery.lottery_service.config;

import java.time.Duration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.PropertyMapper;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.session.autoconfigure.DefaultCookieSerializerCustomizer;
import org.springframework.boot.web.server.Cookie;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.session.web.http.DefaultCookieSerializer;

/**
 * 회원 세션 쿠키를 {@code server.servlet.session.cookie.*}(이름 {@code __Host-lottery_session}, Secure,
 * HttpOnly, SameSite=Lax, 03 6.2)로 만든다. Boot 4는 내장 서버가 없는 실행(MockMvc 테스트 등)을 WAR 배포로 보고 이 설정 대신 서블릿
 * 컨텍스트 값을 써서, 실행 방식마다 쿠키가 달라지지 않게 직접 둔다.
 */
@Configuration(proxyBeanMethods = false)
public class SessionCookieConfig {

  /** 설정 값으로 세션 쿠키 직렬화기를 만든다. */
  @Bean
  public DefaultCookieSerializer cookieSerializer(
      Environment environment, ObjectProvider<DefaultCookieSerializerCustomizer> customizers) {
    Cookie cookie =
        Binder.get(environment)
            .bind("server.servlet.session.cookie", Cookie.class)
            .orElseGet(Cookie::new);
    DefaultCookieSerializer serializer = new DefaultCookieSerializer();
    PropertyMapper map = PropertyMapper.get();
    map.from(cookie::getName).to(serializer::setCookieName);
    map.from(cookie::getDomain).to(serializer::setDomainName);
    map.from(cookie::getPath).to(serializer::setCookiePath);
    map.from(cookie::getHttpOnly).to(serializer::setUseHttpOnlyCookie);
    map.from(cookie::getSecure).to(serializer::setUseSecureCookie);
    map.from(cookie::getMaxAge)
        .as(Duration::getSeconds)
        .as(Math::toIntExact)
        .to(serializer::setCookieMaxAge);
    map.from(cookie::getSameSite).as(Cookie.SameSite::attributeValue).to(serializer::setSameSite);
    map.from(cookie::getPartitioned).to(serializer::setPartitioned);
    customizers.orderedStream().forEach(customizer -> customizer.customize(serializer));
    return serializer;
  }
}
