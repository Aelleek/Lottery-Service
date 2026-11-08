package com.lottery.lottery_service.config;

import com.lottery.lottery_service.account.AccountStore;
import com.lottery.lottery_service.auth.MemberOAuth2UserService;
import com.lottery.lottery_service.auth.MemberPrincipal;
import com.lottery.lottery_service.auth.SessionRevocationFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;

/** 소셜 로그인과 요청별 접근 규칙을 정한다. 로그인한 회원은 {@code MemberPrincipal}로 세션에 남는다. */
@Configuration
public class SecurityConfig {

  private final MemberOAuth2UserService memberOAuth2UserService;
  private final AccountStore accounts;

  /** 회원 로그인 서비스와 계정 저장소로 설정을 만든다. */
  public SecurityConfig(MemberOAuth2UserService memberOAuth2UserService, AccountStore accounts) {
    this.memberOAuth2UserService = memberOAuth2UserService;
    this.accounts = accounts;
  }

  /** 요청 보안 규칙. */
  @Bean
  public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
    http.csrf(AbstractHttpConfigurer::disable)
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
                        "/api/v1/lotto/context")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .oauth2Login(
            oauth2 ->
                oauth2
                    .userInfoEndpoint(u -> u.userService(memberOAuth2UserService))
                    .defaultSuccessUrl("/", false))
        .addFilterBefore(new SessionRevocationFilter(accounts), AnonymousAuthenticationFilter.class)
        .logout(
            logout ->
                logout
                    .addLogoutHandler(
                        (request, response, auth) -> {
                          if (auth != null && auth.getPrincipal() instanceof MemberPrincipal p) {
                            accounts.revokeSessions(p.memberId());
                          }
                        })
                    .logoutSuccessUrl("/")
                    .invalidateHttpSession(true)
                    .deleteCookies("SESSION"));
    return http.build();
  }
}
