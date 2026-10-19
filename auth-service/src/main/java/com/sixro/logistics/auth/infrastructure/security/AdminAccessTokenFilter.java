package com.sixro.logistics.auth.infrastructure.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixro.logistics.auth.domain.exception.AuthErrorCode;
import com.sixro.logistics.auth.domain.exception.AuthException;
import com.sixro.logistics.auth.domain.repository.AccessTokenBlacklistRepository;
import com.sixro.logistics.auth.domain.repository.SessionRepository;
import com.sixro.logistics.auth.infrastructure.jwt.JwtClaims;
import com.sixro.logistics.auth.infrastructure.jwt.JwtProvider;
import com.sixro.logistics.common.core.exception.CommonErrorCode;
import com.sixro.logistics.common.core.exception.ErrorCode;
import com.sixro.logistics.common.core.exception.ErrorResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/** 관리자 생성 요청은 전달된 사용자 헤더 대신 JWT와 Redis 인증 상태를 검증합니다. */
@RequiredArgsConstructor
public class AdminAccessTokenFilter extends OncePerRequestFilter {
    private final JwtProvider jwtProvider;
    private final SessionRepository sessionRepository;
    private final AccessTokenBlacklistRepository blacklistRepository;
    private final ObjectMapper objectMapper;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equals(request.getMethod())
                || !(request.getContextPath() + "/api/v1/users").equals(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        JwtClaims claims;
        try {
            String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
            if (authorization == null || !authorization.startsWith("Bearer ")
                    || authorization.substring(7).isBlank()) {
                throw new AuthException(AuthErrorCode.INVALID_ACCESS_TOKEN);
            }
            claims = jwtProvider.parseAccessToken(authorization.substring(7).trim());
            if (claims.jwtId() == null || claims.jwtId().isBlank()) {
                throw new AuthException(AuthErrorCode.INVALID_ACCESS_TOKEN);
            }
            if (blacklistRepository.exists(claims.jwtId())) {
                throw new AuthException(AuthErrorCode.LOGGED_OUT_TOKEN);
            }
            if (sessionRepository.findSessionIdByUserId(claims.userId())
                    .filter(claims.sessionId()::equals).isEmpty()) {
                throw new AuthException(AuthErrorCode.INVALID_ACCESS_TOKEN);
            }
        } catch (AuthException exception) {
            writeError(response, exception.getErrorCode());
            return;
        } catch (RuntimeException exception) {
            logger.error("관리자 요청의 인증 상태 조회에 실패했습니다.", exception);
            writeError(response, CommonErrorCode.INTERNAL_SERVER_ERROR);
            return;
        }
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(
                claims, null, List.of(new SimpleGrantedAuthority("ROLE_" + claims.role().name()))));
        SecurityContextHolder.setContext(context);
        chain.doFilter(request, response);
    }

    private void writeError(HttpServletResponse response, ErrorCode error) throws IOException {
        response.setStatus(error.getStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getWriter(), ErrorResponse.from(error));
    }
}
