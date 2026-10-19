package com.sixro.logistics.auth.infrastructure.security;

import com.sixro.logistics.auth.application.dto.AdminCreateUserResult;
import com.sixro.logistics.auth.application.service.AdminUserCommandService;
import com.sixro.logistics.auth.domain.exception.AuthErrorCode;
import com.sixro.logistics.auth.domain.exception.AuthException;
import com.sixro.logistics.auth.domain.model.UserRole;
import com.sixro.logistics.auth.domain.model.UserStatus;
import com.sixro.logistics.auth.domain.repository.AccessTokenBlacklistRepository;
import com.sixro.logistics.auth.domain.repository.SessionRepository;
import com.sixro.logistics.auth.infrastructure.config.SecurityConfig;
import com.sixro.logistics.auth.infrastructure.jwt.JwtClaims;
import com.sixro.logistics.auth.infrastructure.jwt.JwtProvider;
import com.sixro.logistics.auth.presentation.controller.AdminUserController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AdminUserController.class)
@Import(SecurityConfig.class)
class AdminUserSecurityTest {
    private static final UUID ADMIN_ID = UUID.randomUUID();
    private static final UUID SESSION_ID = UUID.randomUUID();
    @Autowired MockMvc mvc;
    @MockitoBean JwtProvider jwtProvider;
    @MockitoBean SessionRepository sessionRepository;
    @MockitoBean AccessTokenBlacklistRepository blacklistRepository;
    @MockitoBean AdminUserCommandService service;

    private MockHttpServletRequestBuilder request() {
        return post("/api/v1/users").contentType("application/json").content("""
                {"username":"admin123","password":"Password1!","slackId":"U123",
                 "role":"MASTER_ADMIN"}
                """);
    }

    private void token(UserRole role) {
        when(jwtProvider.parseAccessToken("access-token")).thenReturn(new JwtClaims(
                ADMIN_ID, role, SESSION_ID, "jti", Instant.now().plusSeconds(300)));
        when(sessionRepository.findSessionIdByUserId(ADMIN_ID)).thenReturn(Optional.of(SESSION_ID));
    }

    @Test
    void masterRequestUsesVerifiedIdentityInsteadOfSpoofedHeaders() throws Exception {
        token(UserRole.MASTER_ADMIN);
        when(service.createUser(any())).thenReturn(new AdminCreateUserResult(
                UUID.randomUUID(), "admin123", UserRole.MASTER_ADMIN, UserStatus.APPROVED));
        mvc.perform(request().header("Authorization", "Bearer access-token")
                        .header("X-User-Id", UUID.randomUUID())
                        .header("X-User-Role", "HUB_ADMIN"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.success").value(true));
        verify(service).createUser(argThat(command -> command.requesterId().equals(ADMIN_ID)
                && command.requesterRole() == UserRole.MASTER_ADMIN));
    }

    @Test
    void headersAloneCannotAuthenticate() throws Exception {
        mvc.perform(request().header("X-User-Id", ADMIN_ID).header("X-User-Role", "MASTER_ADMIN"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service, jwtProvider);
    }

    @Test
    void nonMasterCannotElevateRoleWithHeader() throws Exception {
        token(UserRole.HUB_ADMIN);
        mvc.perform(request().header("Authorization", "Bearer access-token")
                        .header("X-User-Role", "MASTER_ADMIN"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test
    void expiredTokenIsRejected() throws Exception {
        when(jwtProvider.parseAccessToken("access-token"))
                .thenThrow(new AuthException(AuthErrorCode.EXPIRED_ACCESS_TOKEN));
        mvc.perform(request().header("Authorization", "Bearer access-token"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("A003"));
        verifyNoInteractions(service, sessionRepository);
    }

    @Test
    void blacklistedTokenIsRejected() throws Exception {
        token(UserRole.MASTER_ADMIN);
        when(blacklistRepository.exists("jti")).thenReturn(true);
        mvc.perform(request().header("Authorization", "Bearer access-token"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("A007"));
        verifyNoInteractions(service);
    }

    @Test
    void replacedSessionIsRejected() throws Exception {
        token(UserRole.MASTER_ADMIN);
        when(sessionRepository.findSessionIdByUserId(ADMIN_ID)).thenReturn(Optional.of(UUID.randomUUID()));
        mvc.perform(request().header("Authorization", "Bearer access-token"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test
    void missingSessionIsRejected() throws Exception {
        token(UserRole.MASTER_ADMIN);
        when(sessionRepository.findSessionIdByUserId(ADMIN_ID)).thenReturn(Optional.empty());
        mvc.perform(request().header("Authorization", "Bearer access-token"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test
    void invalidTokenIsRejected() throws Exception {
        when(jwtProvider.parseAccessToken("access-token"))
                .thenThrow(new AuthException(AuthErrorCode.INVALID_ACCESS_TOKEN));
        mvc.perform(request().header("Authorization", "Bearer access-token"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("A002"));
        verifyNoInteractions(service, sessionRepository, blacklistRepository);
    }

    @Test
    void redisFailureDoesNotAllowUserCreation() throws Exception {
        token(UserRole.MASTER_ADMIN);
        when(blacklistRepository.exists("jti")).thenThrow(new IllegalStateException("Redis unavailable"));
        mvc.perform(request().header("Authorization", "Bearer access-token"))
                .andExpect(status().isInternalServerError());
        verifyNoInteractions(service);
    }
}
