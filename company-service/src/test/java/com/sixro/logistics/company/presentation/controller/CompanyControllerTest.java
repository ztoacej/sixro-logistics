package com.sixro.logistics.company.presentation.controller;

import com.sixro.logistics.company.application.service.CompanyService;
import com.sixro.logistics.common.core.exception.BaseException;
import com.sixro.logistics.common.core.exception.CommonErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import java.util.UUID;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(CompanyController.class)
class CompanyControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean CompanyService service;
    private final UUID id = UUID.randomUUID();
    private final UUID actor = UUID.randomUUID();

    @Test
    void masterUpdatePassesRoleWithoutRequiringAffiliation() throws Exception {
        mvc.perform(patch("/api/v1/companies/" + id).header("X-User-Id", actor)
                        .header("X-User-Role", "MASTER_ADMIN").contentType("application/json")
                        .content("{\"companyName\":\"updated\"}"))
                .andExpect(status().isOk());
        verify(service).updateCompany(eq(id), any(), eq(actor), eq("MASTER_ADMIN"), isNull());
    }

    @Test
    void updatePassesCallerScopeAndPropagatesServiceDenial() throws Exception {
        UUID hub = UUID.randomUUID();
        doThrow(new BaseException(CommonErrorCode.FORBIDDEN)).when(service)
                .updateCompany(eq(id), any(), eq(actor), eq("HUB_ADMIN"), eq(hub));
        mvc.perform(patch("/api/v1/companies/" + id).header("X-User-Id", actor)
                        .header("X-User-Role", "HUB_ADMIN").header("X-Affiliation-Id", hub)
                        .contentType("application/json").content("{\"companyName\":\"updated\"}"))
                .andExpect(status().isForbidden());
        verify(service).updateCompany(eq(id), any(), eq(actor), eq("HUB_ADMIN"), eq(hub));
    }
}
