package com.sixro.logistics.product.presentation.controller;

import com.sixro.logistics.product.application.service.ProductService;
import com.sixro.logistics.product.presentation.dto.response.ProductResponseDto;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.UUID;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ProductController.class)
class ProductControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean ProductService service;
    private static final UUID USER = UUID.randomUUID();
    private static final UUID PRODUCT = UUID.randomUUID();
    private static final UUID COMPANY = UUID.randomUUID();
    private static final UUID HUB = UUID.randomUUID();

    private String body() {
        return """
                {"companyId":"%s","hubId":"%s","productName":"test product","price":1000}
                """.formatted(COMPANY, HUB);
    }

    private MockHttpServletRequestBuilder write(String method, String role) {
        var request = switch (method) {
            case "create" -> post("/api/v1/products");
            case "update" -> patch("/api/v1/products/" + PRODUCT);
            case "delete" -> delete("/api/v1/products/" + PRODUCT);
            default -> throw new IllegalArgumentException(method);
        };
        return request.header("X-User-Id", USER).header("X-User-Role", role)
                .contentType("application/json").content(body());
    }

    @Test
    void masterCanCreateWithoutAffiliationHeader() throws Exception {
        when(service.createProduct(any(), eq(USER))).thenReturn(ProductResponseDto.builder()
                .productId(PRODUCT).companyId(COMPANY).hubId(HUB).build());
        mvc.perform(write("create", "MASTER_ADMIN")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.hubId").value(HUB.toString()));
        verify(service).createProduct(any(), eq(USER));
    }

    @Test
    void masterCanUpdateWithoutAffiliationHeader() throws Exception {
        mvc.perform(write("update", "MASTER_ADMIN")).andExpect(status().isOk());
        verify(service).updateProduct(eq(PRODUCT), any(), eq(USER));
    }

    @Test
    void masterCanDeleteWithoutAffiliationHeader() throws Exception {
        mvc.perform(write("delete", "MASTER_ADMIN")).andExpect(status().isOk());
        verify(service).deleteProduct(PRODUCT, USER);
    }

    @ParameterizedTest
    @CsvSource({"create,HUB_ADMIN", "update,HUB_ADMIN", "delete,HUB_ADMIN",
                "create,COMPANY_MANAGER", "update,COMPANY_MANAGER"})
    void affiliatedRoleWithoutAffiliationIsForbidden(String method, String role) throws Exception {
        mvc.perform(write(method, role)).andExpect(status().isForbidden());
        verify(service, never()).createProduct(any(), any());
        verify(service, never()).updateProduct(any(), any(), any());
        verify(service, never()).deleteProduct(any(), any());
    }

    @ParameterizedTest
    @CsvSource({"create,DELIVERY_MANAGER", "update,DELIVERY_MANAGER", "delete,DELIVERY_MANAGER",
                "create,UNKNOWN", "update,UNKNOWN", "delete,UNKNOWN", "delete,COMPANY_MANAGER"})
    void unsupportedRoleCannotFallThroughWhenAffiliationIsOptional(String method, String role) throws Exception {
        mvc.perform(write(method, role)).andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test
    void hubAdminWithMatchingAffiliationCanCreate() throws Exception {
        mvc.perform(write("create", "HUB_ADMIN").header("X-Affiliation-Id", HUB))
                .andExpect(status().isCreated());
        verify(service).createProduct(any(), eq(USER));
    }

    @Test
    void companyManagerWithMatchingAffiliationCanCreate() throws Exception {
        mvc.perform(write("create", "COMPANY_MANAGER").header("X-Affiliation-Id", COMPANY))
                .andExpect(status().isCreated());
        verify(service).createProduct(any(), eq(USER));
    }

    @Test
    void affiliatedRoleCannotCreateForDifferentHub() throws Exception {
        mvc.perform(write("create", "HUB_ADMIN").header("X-Affiliation-Id", UUID.randomUUID()))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test
    void missingHubInCreateBodyIsStillRejectedForMaster() throws Exception {
        mvc.perform(post("/api/v1/products").header("X-User-Id", USER)
                        .header("X-User-Role", "MASTER_ADMIN").contentType("application/json")
                        .content("{\"companyId\":\"%s\",\"productName\":\"test\",\"price\":1000}".formatted(COMPANY)))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
}
