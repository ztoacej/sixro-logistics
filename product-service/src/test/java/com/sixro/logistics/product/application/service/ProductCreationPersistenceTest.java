package com.sixro.logistics.product.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixro.logistics.product.domain.entity.Product;
import com.sixro.logistics.product.domain.repository.ProductRepository;
import com.sixro.logistics.product.presentation.dto.request.ProductCreateRequestDto;
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

/** mock 저장소가 아닌 DB flush/reload로 hub_id 필수 필드 저장을 검증합니다. */
@DataJpaTest
@ContextConfiguration(classes = ProductCreationPersistenceTest.JpaConfig.class)
@Import(ProductService.class)
class ProductCreationPersistenceTest {
    @Configuration(proxyBeanMethods = false)
    @EntityScan(basePackageClasses = Product.class)
    @EnableJpaRepositories(basePackageClasses = ProductRepository.class)
    @EnableJpaAuditing(auditorAwareRef = "testAuditor")
    static class JpaConfig {
        @Bean AuditorAware<UUID> testAuditor() {
            return () -> Optional.of(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        }
    }

    @Autowired ProductService service;
    @Autowired ProductRepository repository;
    @Autowired EntityManager entityManager;

    @Test
    void createPersistsRequiredHubAndCompanyAndReturnsSameIds() throws Exception {
        UUID hubId = UUID.randomUUID();
        UUID companyId = UUID.randomUUID();
        var request = new ObjectMapper().readValue("""
                {"companyId":"%s","hubId":"%s","productName":"test product","price":1000}
                """.formatted(companyId, hubId), ProductCreateRequestDto.class);
        var result = service.createProduct(request, UUID.randomUUID());
        repository.flush();
        entityManager.clear();
        var stored = repository.findById(result.getProductId()).orElseThrow();
        assertThat(stored.getHubId()).isEqualTo(hubId);
        assertThat(stored.getCompanyId()).isEqualTo(companyId);
        assertThat(result.getHubId()).isEqualTo(hubId);
    }
}
