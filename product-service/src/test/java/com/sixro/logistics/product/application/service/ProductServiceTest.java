package com.sixro.logistics.product.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixro.logistics.product.domain.entity.Product;
import com.sixro.logistics.product.domain.repository.ProductRepository;
import com.sixro.logistics.product.presentation.dto.request.ProductUpdateRequestDto;
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
import org.springframework.test.util.ReflectionTestUtils;
import java.util.Optional;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProductServiceTest {
    @Mock ProductRepository repository;
    @InjectMocks ProductService service;
    private final UUID id = UUID.randomUUID();
    private final UUID hub = UUID.randomUUID();
    private final UUID company = UUID.randomUUID();
    private final UUID actor = UUID.randomUUID();
    private Product product;

    @BeforeEach
    void setUp() {
        product = Product.builder().hubId(hub).companyId(company).productName("original").build();
        ReflectionTestUtils.setField(product, "productId", id);
        when(repository.findActiveForUpdate(id)).thenReturn(Optional.of(product));
    }

    private ProductUpdateRequestDto request(UUID requestHub, UUID requestCompany) throws Exception {
        return new ObjectMapper().readValue("""
                {"hubId":"%s","companyId":"%s","productName":"updated"}
                """.formatted(requestHub, requestCompany), ProductUpdateRequestDto.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"HUB_ADMIN", "COMPANY_MANAGER"})
    void ownAffiliationInBodyCannotAuthorizeAnotherOwnersProduct(String role) throws Exception {
        UUID ownHub = UUID.randomUUID();
        UUID ownCompany = UUID.randomUUID();
        var request = request(ownHub, ownCompany);
        assertForbidden(() -> service.updateProduct(id, request, actor, role,
                role.equals("HUB_ADMIN") ? ownHub : ownCompany));
    }

    @ParameterizedTest
    @ValueSource(strings = {"HUB_ADMIN", "COMPANY_MANAGER", "MASTER_ADMIN"})
    void authorizationUsesStoredAffiliationAndDoesNotTransferProduct(String role) throws Exception {
        var result = service.updateProduct(id, request(UUID.randomUUID(), UUID.randomUUID()), actor, role,
                role.equals("MASTER_ADMIN") ? null : role.equals("HUB_ADMIN") ? hub : company);
        assertThat(result.getProductName()).isEqualTo("updated");
        assertThat(product.getHubId()).isEqualTo(hub);
        assertThat(product.getCompanyId()).isEqualTo(company);
    }

    @ParameterizedTest
    @ValueSource(strings = {"DELIVERY_MANAGER", "UNKNOWN", "HUB_ADMIN", "COMPANY_MANAGER"})
    void unsupportedRoleOrMissingAffiliationIsForbidden(String role) throws Exception {
        var request = request(hub, company);
        assertForbidden(() -> service.updateProduct(id, request, actor, role, null));
    }

    private void assertForbidden(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(BaseException.class,
                ex -> assertThat(ex.getErrorCode()).isEqualTo(CommonErrorCode.FORBIDDEN));
        assertThat(product.getProductName()).isEqualTo("original");
        assertThat(product.getHubId()).isEqualTo(hub);
        assertThat(product.getCompanyId()).isEqualTo(company);
    }
}
