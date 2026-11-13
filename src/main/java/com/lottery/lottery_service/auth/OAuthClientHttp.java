package com.lottery.lottery_service.auth;

import java.time.Duration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.converter.FormHttpMessageConverter;
import org.springframework.security.oauth2.client.endpoint.OAuth2AccessTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest;
import org.springframework.security.oauth2.client.endpoint.RestClientAuthorizationCodeTokenResponseClient;
import org.springframework.security.oauth2.client.http.OAuth2ErrorResponseErrorHandler;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.core.http.converter.OAuth2AccessTokenResponseHttpMessageConverter;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;

/**
 * 소셜 로그인 제공자 호출(토큰 교환, 사용자 정보)에 연결·응답 시간 제한을 둔다(03 6.4). Spring Security의 기본 클라이언트는 시간 제한이 없어, 제공자가
 * 응답하지 않으면 로그인 요청을 처리하는 스레드가 끝없이 묶인다.
 */
public final class OAuthClientHttp {

  /** 연결과 응답 읽기 각각의 시간 제한. */
  public static final Duration TIMEOUT = Duration.ofSeconds(5);

  private OAuthClientHttp() {}

  /** 토큰 교환기. 메시지 변환과 오류 처리는 Spring Security 기본값과 같다. */
  public static OAuth2AccessTokenResponseClient<OAuth2AuthorizationCodeGrantRequest> tokenClient(
      Duration timeout) {
    RestClientAuthorizationCodeTokenResponseClient client =
        new RestClientAuthorizationCodeTokenResponseClient();
    client.setRestClient(
        RestClient.builder()
            .requestFactory(requestFactory(timeout))
            .messageConverters(
                converters -> {
                  converters.clear();
                  converters.add(new FormHttpMessageConverter());
                  converters.add(new OAuth2AccessTokenResponseHttpMessageConverter());
                })
            .defaultStatusHandler(new OAuth2ErrorResponseErrorHandler())
            .build());
    return client;
  }

  /** 사용자 정보 조회기. 오류 처리는 Spring Security 기본값과 같다. */
  public static DefaultOAuth2UserService userService(Duration timeout) {
    RestTemplate template = new RestTemplate(requestFactory(timeout));
    template.setErrorHandler(new OAuth2ErrorResponseErrorHandler());
    DefaultOAuth2UserService service = new DefaultOAuth2UserService();
    service.setRestOperations(template);
    return service;
  }

  private static ClientHttpRequestFactory requestFactory(Duration timeout) {
    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(timeout);
    factory.setReadTimeout(timeout);
    return factory;
  }
}
