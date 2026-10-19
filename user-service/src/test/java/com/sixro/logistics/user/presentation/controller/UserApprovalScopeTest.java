package com.sixro.logistics.user.presentation.controller;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixro.logistics.common.core.response.CommonResponse;
import com.sixro.logistics.user.application.event.UserEventPublisher;
import com.sixro.logistics.user.application.service.*;
import com.sixro.logistics.user.domain.entity.User;
import com.sixro.logistics.user.domain.event.UserApprovedEvent;
import com.sixro.logistics.user.domain.event.UserRejectedEvent;
import com.sixro.logistics.user.domain.model.*;
import com.sixro.logistics.user.domain.repository.UserRepository;
import com.sixro.logistics.user.infrastructure.client.AffiliationClientReader;
import com.sixro.logistics.user.infrastructure.client.response.InternalCompanyResponse;
import com.sixro.logistics.user.infrastructure.client.response.InternalHubResponse;
import com.sixro.logistics.user.presentation.exception.UserExceptionHandler;
import feign.Request;
import feign.RequestTemplate;
import feign.RetryableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Exercises real controller, scope policy and affiliation validation; only external calls/persistence are mocked. */
@ExtendWith(MockitoExtension.class)
class UserApprovalScopeTest {
    private static final UUID TARGET = UUID.randomUUID();
    private static final UUID REVIEWER = UUID.randomUUID();
    private static final UUID HUB = UUID.randomUUID();
    private static final UUID COMPANY = UUID.randomUUID();

    @Mock UserRepository repository;
    @Mock UserReader reader;
    @Mock UserEventPublisher events;
    @Mock AffiliationClientReader clients;
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        var service = new UserCommandService(repository, reader, events, new AffiliationValidationService(clients));
        mvc = MockMvcBuilders.standaloneSetup(new UserController(mock(UserQueryService.class), service))
                .setControllerAdvice(new UserExceptionHandler()).build();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void hubAdminCanReviewOwnHubApplicant(boolean approval) throws Exception {
        User user = target(AffiliationType.HUB, HUB);
        if (approval) when(clients.getHub(HUB)).thenReturn(CommonResponse.success("ok", new InternalHubResponse(HUB, "허브")));
        mvc.perform(request(approval, "HUB_ADMIN", HUB)).andExpect(status().isOk());
        assertDecision(user, approval);
        if (!approval) verifyNoInteractions(clients);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void hubAdminCannotReviewAnotherHubApplicant(boolean approval) throws Exception {
        User user = target(AffiliationType.HUB, UUID.randomUUID());
        mvc.perform(request(approval, "HUB_ADMIN", HUB)).andExpect(status().isForbidden());
        assertUnchanged(user);
        verifyNoInteractions(clients);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void missingRequesterAffiliationIsForbidden(boolean approval) throws Exception {
        User user = target(AffiliationType.HUB, HUB);
        mvc.perform(request(approval, "HUB_ADMIN", null)).andExpect(status().isForbidden());
        assertUnchanged(user);
        verifyNoInteractions(clients);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void companyApplicantScopeUsesCompanyHubFromTheExistingApiContract(boolean approval) throws Exception {
        User user = target(AffiliationType.COMPANY, COMPANY);
        // Company API already returns hubId and address; User must consume hubId rather than compare companyId to hubId.
        String json = "{\"companyId\":\"" + COMPANY + "\",\"hubId\":\"" + HUB
                + "\",\"companyName\":\"업체\",\"address\":\"서울\"}";
        var data = new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .readValue(json, InternalCompanyResponse.class);
        when(clients.getCompany(COMPANY)).thenReturn(CommonResponse.success("ok", data));
        mvc.perform(request(approval, "HUB_ADMIN", HUB)).andExpect(status().isOk());
        assertDecision(user, approval);
        verify(clients, times(1)).getCompany(COMPANY);
        verifyNoMoreInteractions(clients);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void hubAdminCannotReviewCompanyInAnotherHub(boolean approval) throws Exception {
        User user = target(AffiliationType.COMPANY, COMPANY);
        when(clients.getCompany(COMPANY)).thenReturn(CommonResponse.success("ok",
                new InternalCompanyResponse(COMPANY, UUID.randomUUID(), "다른 허브 업체")));
        mvc.perform(request(approval, "HUB_ADMIN", HUB)).andExpect(status().isForbidden());
        assertUnchanged(user);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void companyServiceConnectionFailureDoesNotPermitReview(boolean approval) throws Exception {
        User user = target(AffiliationType.COMPANY, COMPANY);
        Request request = Request.create(Request.HttpMethod.GET, "http://company-service", Map.of(), null,
                java.nio.charset.StandardCharsets.UTF_8, new RequestTemplate());
        when(clients.getCompany(COMPANY)).thenThrow(new RetryableException(-1, "connection refused",
                Request.HttpMethod.GET, (Long) null, request));
        mvc.perform(request(approval, "HUB_ADMIN", HUB)).andExpect(status().isServiceUnavailable());
        assertUnchanged(user);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void missingCompanyHubDoesNotPermitReview(boolean approval) throws Exception {
        User user = target(AffiliationType.COMPANY, COMPANY);
        when(clients.getCompany(COMPANY)).thenReturn(CommonResponse.success("ok",
                new InternalCompanyResponse(COMPANY, null, "허브 누락")));
        mvc.perform(request(approval, "HUB_ADMIN", HUB)).andExpect(status().isServiceUnavailable());
        assertUnchanged(user);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void mismatchedCompanyResponseDoesNotPermitReview(boolean approval) throws Exception {
        User user = target(AffiliationType.COMPANY, COMPANY);
        when(clients.getCompany(COMPANY)).thenReturn(CommonResponse.success("ok",
                new InternalCompanyResponse(UUID.randomUUID(), HUB, "잘못된 응답")));
        mvc.perform(request(approval, "HUB_ADMIN", HUB)).andExpect(status().isServiceUnavailable());
        assertUnchanged(user);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void masterCanReviewWithoutAffiliationHeader(boolean approval) throws Exception {
        User user = target(AffiliationType.COMPANY, COMPANY);
        if (approval) when(clients.getCompany(COMPANY)).thenReturn(CommonResponse.success("ok",
                new InternalCompanyResponse(COMPANY, HUB, "업체")));
        mvc.perform(request(approval, "MASTER_ADMIN", null)).andExpect(status().isOk());
        assertDecision(user, approval);
        if (!approval) verifyNoInteractions(clients);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void emptyCompanyResponseDoesNotPermitReview(boolean approval) throws Exception {
        User user = target(AffiliationType.COMPANY, COMPANY);
        when(clients.getCompany(COMPANY)).thenReturn(CommonResponse.success("ok", null));
        mvc.perform(request(approval, "HUB_ADMIN", HUB)).andExpect(status().isServiceUnavailable());
        assertUnchanged(user);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void openCompanyCircuitDoesNotPermitReview(boolean approval) throws Exception {
        User user = target(AffiliationType.COMPANY, COMPANY);
        var breaker = io.github.resilience4j.circuitbreaker.CircuitBreaker.ofDefaults("companyAffiliation");
        breaker.transitionToOpenState();
        when(clients.getCompany(COMPANY)).thenThrow(
                io.github.resilience4j.circuitbreaker.CallNotPermittedException.createCallNotPermittedException(breaker));
        mvc.perform(request(approval, "HUB_ADMIN", HUB)).andExpect(status().isServiceUnavailable());
        assertUnchanged(user);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void missingTargetAffiliationDoesNotPermitReview(boolean approval) throws Exception {
        User user = target(AffiliationType.HUB, HUB);
        ReflectionTestUtils.setField(user, "affiliationId", null);
        // Approval retains its existing role/affiliation validation; rejection also refuses an unscoped target.
        mvc.perform(request(approval, "HUB_ADMIN", HUB))
                .andExpect(approval ? status().isBadRequest() : status().isForbidden());
        assertUnchanged(user);
        verifyNoInteractions(clients);
    }

    @ParameterizedTest
    @ValueSource(strings = {"COMPANY_MANAGER", "DELIVERY_MANAGER"})
    void nonReviewRolesAreForbiddenEvenWithMatchingHub(String role) throws Exception {
        for (boolean approval : new boolean[]{true, false}) {
            mvc.perform(request(approval, role, HUB)).andExpect(status().isForbidden());
        }
        verifyNoInteractions(reader, events, clients);
    }

    private User target(AffiliationType type, UUID affiliationId) {
        User user = User.create("pending01", "encoded-password", "U-PENDING",
                type == AffiliationType.HUB ? UserRole.DELIVERY_MANAGER : UserRole.COMPANY_MANAGER,
                affiliationId, type);
        ReflectionTestUtils.setField(user, "userId", TARGET);
        when(reader.getAccessibleUserForUpdate(TARGET)).thenReturn(user);
        return user;
    }

    private MockHttpServletRequestBuilder request(boolean approval, String role, UUID affiliation) {
        var request = post("/api/v1/users/{userId}/" + (approval ? "approve" : "reject"), TARGET)
                .header("X-User-Id", REVIEWER).header("X-User-Role", role)
                .contentType(MediaType.APPLICATION_JSON);
        if (affiliation != null) request.header("X-Affiliation-Id", affiliation);
        if (!approval) request.content("{\"rejectedReason\":\"가입 정보 불일치\"}");
        return request;
    }

    private void assertDecision(User user, boolean approval) {
        assertThat(user.getUserStatus()).isEqualTo(approval ? UserStatus.APPROVED : UserStatus.REJECTED);
        if (approval) verify(events).publish(any(UserApprovedEvent.class));
        else verify(events).publish(any(UserRejectedEvent.class));
        verifyNoMoreInteractions(events);
    }

    private void assertUnchanged(User user) {
        assertThat(user.getUserStatus()).isEqualTo(UserStatus.PENDING);
        assertThat(user.getReviewedBy()).isNull();
        verifyNoInteractions(events);
    }
}
