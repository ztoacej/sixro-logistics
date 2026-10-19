package com.sixro.logistics.user.infrastructure.messaging.outbox;

import com.sixro.logistics.user.domain.outbox.OutboxEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.DriverManager;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = OutboxEventRepositoryIntegrationTest.JpaConfig.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class OutboxEventRepositoryIntegrationTest {
    @Configuration(proxyBeanMethods = false)
    @EntityScan(basePackageClasses = OutboxEvent.class)
    @EnableJpaRepositories(basePackageClasses = OutboxEventJpaRepository.class)
    static class JpaConfig { }

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-bookworm")
            .withInitScript("outbox-test-init.sql");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) throws Exception {
        // Upgrade an old table, and prove the additive migration can be applied twice.
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(), POSTGRES.getPassword())) {
            var migration = new ClassPathResource("db/ddl/02_add_outbox_retry_schedule.sql");
            ScriptUtils.executeSqlScript(connection, migration);
            ScriptUtils.executeSqlScript(connection, migration);
        }
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);
    }

    @Autowired OutboxEventJpaRepository repository;
    @Autowired PlatformTransactionManager transactionManager;
    private final LocalDateTime now = LocalDateTime.of(2026, 10, 19, 0, 0);

    @BeforeEach
    void clear() { repository.deleteAllInBatch(); }

    @Test
    void dueFailedAndLegacyFailedAreSelectedButFutureAndPublishedAreExcluded() {
        var due = failed();
        ReflectionTestUtils.setField(due, "nextAttemptAt", now.minusSeconds(1));
        var legacy = failed();
        ReflectionTestUtils.setField(legacy, "nextAttemptAt", null);
        var future = failed(); // now + 1 minute
        var published = failed();
        published.markPublished();
        var pending = OutboxEvent.create(UUID.randomUUID(), "USER", "USER_CREATED", "{}");
        repository.saveAllAndFlush(java.util.List.of(due, legacy, future, published, pending));
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                assertThat(repository.findPublishableEventsForUpdate(now))
                        .extracting(OutboxEvent::getEventId)
                        .containsExactlyInAnyOrder(due.getEventId(), legacy.getEventId(), pending.getEventId()));
    }

    @Test
    void retryScheduleAndRecoveryStateSurviveDatabaseCommit() {
        var event = failed();
        ReflectionTestUtils.setField(event, "nextAttemptAt", null);
        repository.saveAndFlush(event);
        var tx = new TransactionTemplate(transactionManager);
        tx.executeWithoutResult(status -> {
            var selected = repository.findPublishableEventsForUpdate(now).getFirst();
            selected.recordPublishFailure(now, Duration.ofSeconds(5), Duration.ofMinutes(1));
            repository.saveAndFlush(selected);
        });
        tx.executeWithoutResult(status ->
                assertThat(repository.findPublishableEventsForUpdate(now.plusSeconds(59))).isEmpty());
        tx.executeWithoutResult(status -> {
            var selected = repository.findPublishableEventsForUpdate(now.plusMinutes(1)).getFirst();
            assertThat(selected.getRetryCount()).isEqualTo(3);
            selected.markPublished();
            repository.saveAndFlush(selected);
        });
        tx.executeWithoutResult(status ->
                assertThat(repository.findPublishableEventsForUpdate(now.plusMinutes(2))).isEmpty());
    }

    @Test
    void lockedEventIsSkippedByAnotherPublisher() {
        var event = failed();
        ReflectionTestUtils.setField(event, "nextAttemptAt", null);
        repository.saveAndFlush(event);
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            assertThat(repository.findPublishableEventsForUpdate(now)).hasSize(1);
            var other = CompletableFuture.supplyAsync(() -> new TransactionTemplate(transactionManager)
                    .execute(inner -> repository.findPublishableEventsForUpdate(now).size()));
            try {
                assertThat(other.get(5, TimeUnit.SECONDS)).isZero();
            } catch (Exception exception) { throw new IllegalStateException(exception); }
        });
    }

    private OutboxEvent failed() {
        var event = OutboxEvent.create(UUID.randomUUID(), "USER", "USER_DEACTIVATED", "{}");
        for (int i = 0; i < 3; i++)
            event.recordPublishFailure(now, Duration.ofSeconds(5), Duration.ofMinutes(1));
        return event;
    }
}
