package com.sixro.logistics.auth.infrastructure.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixro.logistics.auth.domain.repository.AccessTokenBlacklistRepository;
import com.sixro.logistics.auth.domain.repository.SessionRepository;
import com.sixro.logistics.auth.infrastructure.jwt.JwtProvider;
import com.sixro.logistics.auth.infrastructure.security.AdminAccessTokenFilter;
import com.sixro.logistics.common.core.exception.CommonErrorCode;
import com.sixro.logistics.common.core.exception.ErrorResponse;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Auth Service의 Spring Security 기본 정책을 설정합니다.
 * <p>
 * 공개 인증 API와 JWT 인증이 필요한 관리자 생성 API를 구분하고,
 * 서버 세션은 사용하지 않도록 설정합니다.
 *
 * <p>운영 환경에서는 Auth Service에 대한 직접 외부 접근이
 * 인프라 또는 네트워크 수준에서 차단되어야 합니다.</p>
 */
@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            JwtProvider jwtProvider,
            SessionRepository sessionRepository,
            AccessTokenBlacklistRepository blacklistRepository,
            ObjectMapper objectMapper
    ) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .formLogin(formLogin -> formLogin.disable())
                .httpBasic(httpBasic -> httpBasic.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(
                        SessionCreationPolicy.STATELESS
                ))
                .addFilterBefore(new AdminAccessTokenFilter(
                        jwtProvider, sessionRepository, blacklistRepository, objectMapper
                ), UsernamePasswordAuthenticationFilter.class)
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, exception) -> {
                            response.setStatus(401);
                            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                            objectMapper.writeValue(response.getWriter(),
                                    ErrorResponse.from(CommonErrorCode.UNAUTHORIZED));
                        })
                        .accessDeniedHandler((request, response, exception) -> {
                            response.setStatus(403);
                            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                            objectMapper.writeValue(response.getWriter(),
                                    ErrorResponse.from(CommonErrorCode.FORBIDDEN));
                        }))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.POST, "/api/v1/users")
                        .hasRole("MASTER_ADMIN")
                        .requestMatchers(
                                "/api/v1/auth/**",
                                "/actuator/health",
                                "/swagger-ui/**",
                                "/swagger-ui.html",
                                "/v3/api-docs/**"
                        ).permitAll()
                        .anyRequest().authenticated()
                )
                .build();
    }
}