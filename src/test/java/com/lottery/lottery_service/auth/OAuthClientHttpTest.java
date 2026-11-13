package com.lottery.lottery_service.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.lottery.lottery_service.support.SlowBodyServer;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.endpoint.OAuth2AccessTokenResponse;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationExchange;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationResponse;
import org.springframework.security.oauth2.core.user.OAuth2User;

/** 소셜 로그인 제공자 호출의 시간 제한과, 바꾼 클라이언트가 기본 클라이언트처럼 응답을 읽는지 가짜 제공자로 확인한다(03 6.4). */
class OAuthClientHttpTest {

  private static final Duration SHORT = Duration.ofSeconds(1);

  private final AtomicReference<String> tokenBody =
      new AtomicReference<>(
          "{\"access_token\":\"at\",\"token_type\":\"Bearer\",\"expires_in\":3600}");
  private final AtomicReference<Boolean> hang = new AtomicReference<>(false);
  private final AtomicInteger userInfoCalls = new AtomicInteger();
  private HttpServer server;

  @BeforeEach
  void start() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.setExecutor(Executors.newCachedThreadPool());
    server.createContext(
        "/token",
        exchange -> {
          exchange.getRequestBody().readAllBytes();
          if (hang.get()) {
            sleep();
          }
          reply(exchange, tokenBody.get());
        });
    server.createContext(
        "/userinfo",
        exchange -> {
          userInfoCalls.incrementAndGet();
          if (hang.get()) {
            sleep();
          }
          reply(exchange, "{\"id\":12345}");
        });
    server.start();
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  @Test
  @DisplayName("토큰 교환과 사용자 정보 조회는 정상 응답을 기본 클라이언트처럼 읽는다")
  void readsNormalResponses() {
    OAuth2AccessTokenResponse token =
        OAuthClientHttp.tokenClient(SHORT).getTokenResponse(grantRequest());
    OAuth2User user =
        OAuthClientHttp.userService(SHORT)
            .loadUser(new OAuth2UserRequest(registration(), access()));

    assertThat(token.getAccessToken().getTokenValue()).isEqualTo("at");
    assertThat(user.getName()).isEqualTo("12345");
  }

  @Test
  @DisplayName("제공자 오류 응답은 기본 클라이언트처럼 OAuth 오류로 바뀐다")
  void mapsProviderErrors() {
    tokenBody.set("{\"error\":\"invalid_grant\"}");

    assertThatThrownBy(() -> OAuthClientHttp.tokenClient(SHORT).getTokenResponse(grantRequest()))
        .hasMessageContaining("invalid_grant");
  }

  @Test
  @DisplayName("제공자가 응답하지 않으면 시간 제한 뒤 실패해 로그인 요청 스레드가 묶이지 않는다")
  void timesOutWhenProviderHangs() {
    hang.set(true);

    assertTimeoutPreemptively(
        Duration.ofSeconds(5),
        () ->
            assertThatThrownBy(
                    () -> OAuthClientHttp.tokenClient(SHORT).getTokenResponse(grantRequest()))
                .isInstanceOf(RuntimeException.class));
    assertTimeoutPreemptively(
        Duration.ofSeconds(5),
        () ->
            assertThatThrownBy(
                    () ->
                        OAuthClientHttp.userService(SHORT)
                            .loadUser(new OAuth2UserRequest(registration(), access())))
                .isInstanceOf(RuntimeException.class));
  }

  @Test
  @DisplayName("본문을 아주 천천히 보내도 전체 시간 상한 뒤 실패한다")
  void slowBodyHitsTotalDeadline() throws Exception {
    HttpServer slow = SlowBodyServer.start(1_000);
    try {
      ClientRegistration dripping = registration("http://127.0.0.1:" + slow.getAddress().getPort());
      long started = System.nanoTime();

      assertThatThrownBy(
              () ->
                  OAuthClientHttp.userService(SHORT)
                      .loadUser(new OAuth2UserRequest(dripping, access())))
          .isInstanceOf(RuntimeException.class);
      assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(4));
    } finally {
      slow.stop(0);
    }
  }

  @Test
  @DisplayName("크기 상한을 넘는 응답과 주소 이동(3xx)은 받지 않는다. 이동한 곳은 부르지 않는다")
  void rejectsOversizedAndRedirect() {
    server.createContext(
        "/big",
        exchange -> {
          byte[] out = ("{\"id\":\"" + "x".repeat(OAuthClientHttp.MAX_BODY) + "\"}").getBytes();
          exchange.sendResponseHeaders(200, out.length);
          exchange.getResponseBody().write(out);
          exchange.close();
        });
    server.createContext(
        "/moved",
        exchange -> {
          exchange.getResponseHeaders().add("Location", "/userinfo");
          exchange.sendResponseHeaders(302, -1);
          exchange.close();
        });
    String base = "http://127.0.0.1:" + server.getAddress().getPort();

    assertThatThrownBy(
            () ->
                OAuthClientHttp.userService(SHORT)
                    .loadUser(new OAuth2UserRequest(registration(base, "/big"), access())))
        .isInstanceOf(RuntimeException.class);
    assertThatThrownBy(
            () ->
                OAuthClientHttp.userService(SHORT)
                    .loadUser(new OAuth2UserRequest(registration(base, "/moved"), access())))
        .isInstanceOf(RuntimeException.class);
    assertThat(userInfoCalls.get()).isZero();
  }

  private ClientRegistration registration() {
    return registration("http://127.0.0.1:" + server.getAddress().getPort());
  }

  private ClientRegistration registration(String base) {
    return registration(base, "/userinfo");
  }

  private ClientRegistration registration(String base, String userInfoPath) {
    return ClientRegistration.withRegistrationId("kakao")
        .clientId("client")
        .clientSecret("secret")
        .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
        .redirectUri("http://localhost/login/oauth2/code/kakao")
        .authorizationUri(base + "/authorize")
        .tokenUri(base + "/token")
        .userInfoUri(base + userInfoPath)
        .userNameAttributeName("id")
        .build();
  }

  private OAuth2AuthorizationCodeGrantRequest grantRequest() {
    ClientRegistration registration = registration();
    OAuth2AuthorizationRequest request =
        OAuth2AuthorizationRequest.authorizationCode()
            .authorizationUri(registration.getProviderDetails().getAuthorizationUri())
            .clientId("client")
            .redirectUri(registration.getRedirectUri())
            .state("s")
            .build();
    OAuth2AuthorizationResponse response =
        OAuth2AuthorizationResponse.success("code")
            .redirectUri(registration.getRedirectUri())
            .state("s")
            .build();
    return new OAuth2AuthorizationCodeGrantRequest(
        registration, new OAuth2AuthorizationExchange(request, response));
  }

  private static OAuth2AccessToken access() {
    return new OAuth2AccessToken(
        OAuth2AccessToken.TokenType.BEARER, "at", Instant.now(), Instant.now().plusSeconds(60));
  }

  private static void reply(com.sun.net.httpserver.HttpExchange exchange, String body)
      throws java.io.IOException {
    byte[] out = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(body.contains("\"error\"") ? 400 : 200, out.length);
    exchange.getResponseBody().write(out);
    exchange.close();
  }

  private static void sleep() {
    try {
      Thread.sleep(30_000);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
