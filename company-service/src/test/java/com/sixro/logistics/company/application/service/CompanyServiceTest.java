package com.sixro.logistics.company.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixro.logistics.company.domain.entity.Company;
import com.sixro.logistics.company.domain.repository.CompanyRepository;
import com.sixro.logistics.company.presentation.dto.request.CompanyUpdateRequestDto;
import com.sixro.logistics.common.core.exception.BaseException;
import com.sixro.logistics.common.core.exception.CommonErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.util.Optional;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CompanyServiceTest {
    @Mock CompanyRepository repository;
    @InjectMocks CompanyService service;
    private final UUID id = UUID.randomUUID();
    private final UUID hub = UUID.randomUUID();
    private final UUID actor = UUID.randomUUID();
    private Company company;

    @BeforeEach
    void setUp() {
        company = Company.builder().companyId(id).hubId(hub).companyName("original").build();
        when(repository.findActiveForUpdate(id)).thenReturn(Optional.of(company));
    }

    private CompanyUpdateRequestDto request(UUID requestedHub) throws Exception {
        String hubField = requestedHub == null ? "" : ",\"hubId\":\"" + requestedHub + "\"";
        return new ObjectMapper().readValue("{\"companyName\":\"updated\"" + hubField + "}", CompanyUpdateRequestDto.class);
    }

    @Test
    void ownHubInBodyCannotAuthorizeAnotherHubsCompany() throws Exception {
        UUID otherHub = UUID.randomUUID();
        var request = request(otherHub);
        assertForbidden(() -> service.updateCompany(id, request, actor, "HUB_ADMIN", otherHub));
    }

    @Test
    void companyManagerCannotUpdateAnotherCompany() throws Exception {
        var request = request(hub);
        assertForbidden(() -> service.updateCompany(id, request, actor, "COMPANY_MANAGER", UUID.randomUUID()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"HUB_ADMIN", "COMPANY_MANAGER"})
    void authorizedNonMasterCannotTransferCompanyToAnotherHub(String role) throws Exception {
        var request = request(UUID.randomUUID());
        assertForbidden(() -> service.updateCompany(id, request, actor, role,
                role.equals("HUB_ADMIN") ? hub : id));
    }

    @ParameterizedTest
    @ValueSource(strings = {"HUB_ADMIN", "COMPANY_MANAGER"})
    void authorizedRoleCanUpdateWithoutResendingHub(String role) throws Exception {
        var result = service.updateCompany(id, request(null), actor, role,
                role.equals("HUB_ADMIN") ? hub : id);
        assertThat(result.getCompanyName()).isEqualTo("updated");
        assertThat(company.getHubId()).isEqualTo(hub);
    }

    @Test
    void masterCanTransferHubWithoutOwnAffiliation() throws Exception {
        UUID nextHub = UUID.randomUUID();
        service.updateCompany(id, request(nextHub), actor, "MASTER_ADMIN", null);
        assertThat(company.getHubId()).isEqualTo(nextHub);
        assertThat(company.getCompanyName()).isEqualTo("updated");
    }

    @ParameterizedTest
    @ValueSource(strings = {"DELIVERY_MANAGER", "UNKNOWN", "HUB_ADMIN", "COMPANY_MANAGER"})
    void unsupportedRoleOrMissingAffiliationIsForbidden(String role) throws Exception {
        var request = request(hub);
        assertForbidden(() -> service.updateCompany(id, request, actor, role, null));
    }

    private void assertForbidden(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(BaseException.class,
                ex -> assertThat(ex.getErrorCode()).isEqualTo(CommonErrorCode.FORBIDDEN));
        assertThat(company.getCompanyName()).isEqualTo("original");
        assertThat(company.getHubId()).isEqualTo(hub);
    }
}
