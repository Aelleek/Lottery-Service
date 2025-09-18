package com.lottery.lottery_service.config;


import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import com.lottery.lottery_service.auth.CustomOAuth2UserService;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Spring Security 설정 클래스
 * - OAuth2 기반 소셜 로그인(Google, Kakao, Naver) 설정
 * - H2 콘솔 접근 허용
 * - 정적 리소스 및 비로그인 페이지 접근 허용
 */
/**
 * 목적: OAuth2 로그인 성공 후 사용자 정보를 우리 커스텀 서비스로 처리하도록 연결한다.
 * - userInfoEndpoint.userService(customOAuth2UserService): 업서트 로직 진입점
 * - defaultSuccessUrl("/", true): 로그인 성공 시 리다이렉트
 */
@Configuration
@RequiredArgsConstructor
public class SecurityConfig {

    private final CustomOAuth2UserService customOAuth2UserService;
    private final Environment env;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                // CSRF 보호 기능 비활성화 (개발 단계에서는 편의상 꺼두고, 운영 시에는 꼭 활성화 권장)
                .csrf(AbstractHttpConfigurer::disable)

                // (개발) H2 콘솔은 frame-ancestors 허용
                .headers(h -> h.frameOptions(f -> f.sameOrigin()))

//                // CSRF: 쿠키 토큰 발급 + 일부 경로는 예외
//                .csrf(csrf -> csrf
//                        .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
//                        .ignoringRequestMatchers(
//                                new AntPathRequestMatcher("/h2-console/**"),
//                                new AntPathRequestMatcher("/oauth2/**"),
//                                new AntPathRequestMatcher("/login/oauth2/**")
//                        )
//                )

                // 요청별 권한 설정
                .authorizeHttpRequests(authorize -> authorize
                        // 비로그인 상태에서도 접근 가능한 URL
                        .requestMatchers("/", "/login**", "/css/**", "/js/**", "/images/**", "/h2-console/**").permitAll()
                        // 위 경로 외의 모든 요청은 인증 필요
                        .anyRequest().authenticated()
                )

                // OAuth2 로그인 설정
                .oauth2Login(oauth2 -> oauth2
                        // ★ 여기 추가: 로그인 성공 후 사용자 정보 로딩을 커스텀 서비스로 처리
                        .userInfoEndpoint(u -> u.userService(customOAuth2UserService))
                        // 사용자 지정 로그인 페이지 (있다면)
                        .loginPage("/login")
                        // 로그인 성공 시 리다이렉트할 기본 경로
                        // 로그인 전에 보던 페이지로 돌아가고 싶다면 true → false
                        .defaultSuccessUrl("/", false)
                )

                // 로그아웃 설정
                .logout(logout -> logout
                        .logoutSuccessUrl("/")
                        .invalidateHttpSession(true)
                        .deleteCookies("JSESSIONID", "XSRF-TOKEN")
                );

        // http 빌드 직후(체인 끝나기 전)에 추가
//        http.addFilterAfter(new CsrfCookieFilter(), CsrfFilter.class);

        return http.build();
    }


//    private static final class CsrfCookieFilter extends OncePerRequestFilter {
//        @Override
//        protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
//                throws ServletException, IOException {
//
//            CsrfToken token = (CsrfToken) req.getAttribute(CsrfToken.class.getName());
//            if (token == null) {
//                token = (CsrfToken) req.getAttribute("_csrf"); // 호환
//            }
//            if (token != null) {
//                token.getToken(); // ★ 이 호출이 있어야 XSRF-TOKEN이 생성/Set-Cookie 됨
//            }
//            chain.doFilter(req, res);
//        }
//    }
}