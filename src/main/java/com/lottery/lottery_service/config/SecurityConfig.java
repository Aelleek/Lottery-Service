package com.lottery.lottery_service.config;


import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Spring Security 설정 클래스
 * - OAuth2 기반 소셜 로그인(Google, Kakao, Naver) 설정
 * - H2 콘솔 접근 허용
 * - 정적 리소스 및 비로그인 페이지 접근 허용
 */
@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                // CSRF 보호 기능 비활성화 (개발 단계에서는 편의상 꺼두고, 운영 시에는 꼭 활성화 권장)
                .csrf(AbstractHttpConfigurer::disable)

                // 요청별 권한 설정
                .authorizeHttpRequests(authorize -> authorize
                        // 비로그인 상태에서도 접근 가능한 URL (정적 리소스 및 로그인 관련 경로)
                        .requestMatchers(
                                "/", "/login**", "/css/**", "/js/**", "/images/**", "/h2-console/**"
                        ).permitAll()

                        // 위 경로 외의 모든 요청은 인증 필요
                        .anyRequest().authenticated()
                )

                // OAuth2 로그인 설정
                .oauth2Login(oauth2 -> oauth2
                        // 사용자 지정 로그인 페이지 경로 (기본 로그인 페이지를 그대로 쓰는 경우 생략 가능)
                        .loginPage("/login")

                        // 로그인 성공 시 리다이렉트할 기본 경로
                        .defaultSuccessUrl("/", true)
                )

                // 로그아웃 설정
                .logout(logout -> logout
                        // 로그아웃 성공 시 이동할 경로
                        .logoutSuccessUrl("/")

                        // 세션 무효화
                        .invalidateHttpSession(true)

                        // 인증 쿠키 삭제
                        .deleteCookies("JSESSIONID")
                );

        // H2 콘솔 사용을 위해 frame 옵션 비활성화
        http.headers(headers -> headers
                .frameOptions(HeadersConfigurer.FrameOptionsConfig::disable)
        );
        return http.build();
    }
}