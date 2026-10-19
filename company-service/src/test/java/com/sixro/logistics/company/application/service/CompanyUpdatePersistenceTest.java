package com.sixro.logistics.company.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixro.logistics.company.domain.entity.Company;
import com.sixro.logistics.company.domain.entity.CompanyType;
import com.sixro.logistics.company.domain.repository.CompanyRepository;
import com.sixro.logistics.company.presentation.dto.request.CompanyUpdateRequestDto;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;
import java.util.Optional;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ContextConfiguration(classes = CompanyUpdatePersistenceTest.JpaConfig.class)
@Import(CompanyService.class)
class CompanyUpdatePersistenceTest {
    @Configuration(proxyBeanMethods = false)
    @EntityScan(basePackageClasses = Company.class)
    @EnableJpaRepositories(basePackageClasses = CompanyRepository.class)
    @EnableJpaAuditing(auditorAwareRef = "testAuditor")
    static class JpaConfig {
        @Bean AuditorAware<UUID> testAuditor() {
            return () -> Optional.of(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        }
    }
    @Autowired CompanyRepository repository;
    @Autowired CompanyService service;
    @Autowired EntityManager entityManager;

    @Test
    void ownHubUpdateExecutesLockingQueryAndRetainsExistingAffiliation() throws Exception {
        UUID hub = UUID.randomUUID();
        var company = repository.saveAndFlush(Company.builder().hubId(hub)
                .companyName("original").companyType(CompanyType.PRODUCER).businessNumber("1234567890")
                .zipcode("12345").address("address").detailAddress("detail")
                .contactName("name").contactPhone("01012345678").build());
        UUID id = company.getCompanyId();
        entityManager.clear();
        var request = new ObjectMapper().readValue("{\"companyName\":\"updated\"}", CompanyUpdateRequestDto.class);
        service.updateCompany(id, request, UUID.randomUUID(), "HUB_ADMIN", hub);
        repository.flush();
        entityManager.clear();
        var stored = repository.findById(id).orElseThrow();
        assertThat(stored.getCompanyName()).isEqualTo("updated");
        assertThat(stored.getHubId()).isEqualTo(hub);
    }
}
