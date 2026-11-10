package com.lottery.lottery_service.config;

import com.lottery.lottery_service.account.AccountStore;
import com.lottery.lottery_service.account.GuestCredentialStore;
import com.lottery.lottery_service.account.GuestIssueService;
import com.lottery.lottery_service.account.SubjectClaimStore;
import com.lottery.lottery_service.auth.CsrfFilter;
import com.lottery.lottery_service.auth.CsrfTokens;
import com.lottery.lottery_service.auth.GuestAuthenticationFilter;
import com.lottery.lottery_service.auth.LoginFailureHandler;
import com.lottery.lottery_service.auth.LoginSuccessHandler;
import com.lottery.lottery_service.auth.MemberOAuth2UserService;
import com.lottery.lottery_service.auth.MemberPrincipal;
import com.lottery.lottery_service.auth.ReauthAuthorizationRequestResolver;
import com.lottery.lottery_service.auth.SessionRevocationFilter;
import com.lottery.lottery_service.common.error.BusinessException;
import com.lottery.lottery_service.common.error.ErrorCode;
import com.lottery.lottery_service.common.web.OriginGuard;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.logout.LogoutFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.security.web.header.writers.StaticHeadersWriter;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.NegatedRequestMatcher;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * 소셜 로그인과 요청별 접근 규칙을 정한다. 회원은 {@code MemberPrincipal}로 세션에, 비회원은 요청마다 자격 쿠키로 확인한다. 변경 요청의 CSRF는
 * Spring 기본 방식 대신 {@link CsrfFilter}가 서명 토큰으로 확인한다(D-051).
 */
@Configuration
public class SecurityConfig {

  /** 응답에 붙이는 CSP(03 6.2, C-29). 인라인 스크립트·스타일을 쓰지 않는 것을 전제로 한다. */
  public static final String CONTENT_SECURITY_POLICY =
      "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:;"
          + " connect-src 'self'; object-src 'none'; base-uri 'self'; frame-ancestors 'none'";

  private final MemberOAuth2UserService memberOAuth2UserService;
  private final AccountStore accounts;
  private final GuestCredentialStore guestCredentials;
  private final GuestIssueService guestIssuer;
  private final CsrfTokens csrfTokens;
  private final OriginGuard originGuard;
  private final HandlerExceptionResolver errors;
  private final SubjectClaimStore claims;
  private final ClientRegistrationRepository registrations;
  private final Clock clock;

  /** 보안 설정에 쓰는 구성 요소로 만든다. */
  public SecurityConfig(
      MemberOAuth2UserService memberOAuth2UserService,
      AccountStore accounts,
      GuestCredentialStore guestCredentials,
      GuestIssueService guestIssuer,
      CsrfTokens csrfTokens,
      OriginGuard originGuard,
      @Qualifier("handlerExceptionResolver") HandlerExceptionResolver errors,
      SubjectClaimStore claims,
      ClientRegistrationRepository registrations,
      Clock clock) {
    this.memberOAuth2UserService = memberOAuth2UserService;
    this.accounts = accounts;
    this.guestCredentials = guestCredentials;
    this.guestIssuer = guestIssuer;
    this.csrfTokens = csrfTokens;
    this.originGuard = originGuard;
    this.errors = errors;
    this.claims = claims;
    this.registrations = registrations;
    this.clock = clock;
  }

  /** 요청 보안 규칙. */
  @Bean
  public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
    http.csrf(AbstractHttpConfigurer::disable)
        .headers(
            h ->
                h.contentSecurityPolicy(csp -> csp.policyDirectives(CONTENT_SECURITY_POLICY))
                    .frameOptions(f -> f.deny())
                    .referrerPolicy(r -> r.policy(ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                    .httpStrictTransportSecurity(
                        hsts -> hsts.includeSubDomains(true).maxAgeInSeconds(31_536_000))
                    .addHeaderWriter(
                        new StaticHeadersWriter(
                            "Permissions-Policy", "camera=(self), microphone=(), geolocation=()")))
        .authorizeHttpRequests(
            authorize ->
                authorize
                    .requestMatchers(
                        "/",
                        "/index.html",
                        "/error",
                        "/favicon.ico",
                        "/css/**",
                        "/js/**",
                        "/images/**",
                        "/api/v1/lotto/context",
                        "/api/v1/session",
                        "/api/v1/guest-credentials")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .requestCache(c -> c.requestCache(pageRequestCache()))
        .exceptionHandling(
            e ->
                e.defaultAuthenticationEntryPointFor(
                        (request, response, ex) ->
                            errors.resolveException(
                                request,
                                response,
                                null,
                                new BusinessException(ErrorCode.UNAUTHENTICATED, "로그인이 필요합니다.")),
                        new AntPathRequestMatcher("/api/**"))
                    .defaultAccessDeniedHandlerFor(
                        (request, response, ex) ->
                            errors.resolveException(
                                request,
                                response,
                                null,
                                new BusinessException(ErrorCode.FORBIDDEN, "권한이 없습니다.")),
                        new AntPathRequestMatcher("/api/**")))
        .addFilterBefore(new SessionRevocationFilter(accounts), LogoutFilter.class)
        .addFilterBefore(
            new GuestAuthenticationFilter(guestCredentials, guestIssuer), LogoutFilter.class)
        .addFilterBefore(new CsrfFilter(csrfTokens, originGuard, errors), LogoutFilter.class)
        .oauth2Login(
            oauth2 ->
                oauth2
                    .authorizationEndpoint(
                        a ->
                            a.authorizationRequestResolver(
                                new ReauthAuthorizationRequestResolver(registrations)))
                    .userInfoEndpoint(u -> u.userService(memberOAuth2UserService))
                    .successHandler(
                        new LoginSuccessHandler(guestCredentials, guestIssuer, claims, clock))
                    .failureHandler(new LoginFailureHandler()))
        .logout(
            logout ->
                logout
                    .logoutRequestMatcher(new AntPathRequestMatcher("/logout", "POST"))
                    .addLogoutHandler(
                        (request, response, auth) -> {
                          if (auth != null && auth.getPrincipal() instanceof MemberPrincipal p) {
                            accounts.revokeSessions(p.memberId());
                          }
                        })
                    .logoutSuccessUrl("/")
                    .invalidateHttpSession(true)
                    .deleteCookies("__Host-lottery_session", "lottery_session"));
    return http.build();
  }

  private static HttpSessionRequestCache pageRequestCache() {
    HttpSessionRequestCache cache = new HttpSessionRequestCache();
    cache.setRequestMatcher(new NegatedRequestMatcher(new AntPathRequestMatcher("/api/**")));
    return cache;
  }
}
