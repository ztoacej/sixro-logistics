package com.sixro.logistics.user.infrastructure.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixro.logistics.common.core.exception.BaseException;
import com.sixro.logistics.user.application.command.ApproveUserCommand;
import com.sixro.logistics.user.application.command.RejectUserCommand;
import com.sixro.logistics.user.application.service.*;
import com.sixro.logistics.user.domain.entity.User;
import com.sixro.logistics.user.domain.exception.UserErrorCode;
import com.sixro.logistics.user.domain.model.*;
import com.sixro.logistics.user.domain.outbox.OutboxEvent;
import com.sixro.logistics.user.infrastructure.messaging.outbox.*;
import com.sixro.logistics.user.infrastructure.persistence.adapter.UserRepositoryAdapter;
import com.sixro.logistics.user.infrastructure.persistence.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.*;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.DriverManager;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;

@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = UserApprovalConcurrencyIntegrationTest.Config.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class UserApprovalConcurrencyIntegrationTest {
    private static final UUID REVIEWER = UUID.randomUUID();

    @Configuration(proxyBeanMethods = false)
    @EntityScan(basePackageClasses = {User.class, OutboxEvent.class})
    @EnableJpaRepositories(basePackageClasses = {JpaUserRepository.class, OutboxEventJpaRepository.class})
    @EnableJpaAuditing
    @Import({UserCommandService.class, UserReader.class, UserRepositoryAdapter.class,
            OutboxUserEventPublisher.class, OutboxEventRepositoryAdapter.class})
    static class Config {
        @Bean AuditorAware<UUID> auditorAware() { return () -> Optional.of(REVIEWER); }
        @Bean ObjectMapper objectMapper() { return new ObjectMapper().findAndRegisterModules(); }
    }

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-bookworm")
            .withInitScript("user-approval-test-init.sql");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);
    }

    @Autowired UserCommandService service;
    @Autowired JpaUserRepository users;
    @Autowired OutboxEventJpaRepository events;
    @Autowired PlatformTransactionManager transactionManager;
    @MockitoBean AffiliationValidationService affiliations;
    @MockitoBean UserQueryRepository queries;

    @BeforeEach
    void clear() {
        events.deleteAllInBatch();
        users.deleteAllInBatch();
    }

    @ParameterizedTest
    @CsvSource({"true,true", "true,false", "false,true", "false,false"})
    void concurrentDecisionsPersistOnlyTheFirstDecisionAndOneEvent(boolean firstApproval,
                                                                  boolean secondApproval) throws Exception {
        UUID target = pendingUser();
        var changed = new CountDownLatch(1);
        var commit = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
                decide(target, firstApproval);
                changed.countDown();
                await(commit);
            }));
            try {
                assertThat(changed.await(10, TimeUnit.SECONDS)).isTrue();
                var second = executor.submit(() -> catchThrowable(() -> decide(target, secondApproval)));
                // Prove the competing transaction reached PostgreSQL and is actually waiting on the row lock.
                awaitDatabaseLock();
                assertThat(second.isDone()).isFalse();
                commit.countDown();
                first.get(10, TimeUnit.SECONDS);
                assertThat(second.get(10, TimeUnit.SECONDS))
                        .isInstanceOfSatisfying(BaseException.class, error -> assertThat(error.getErrorCode())
                                .isEqualTo(secondApproval ? UserErrorCode.INVALID_APPROVE_STATUS
                                        : UserErrorCode.INVALID_REJECT_STATUS));
            } finally {
                commit.countDown();
            }
        }
        assertThat(users.findById(target).orElseThrow().getUserStatus())
                .isEqualTo(firstApproval ? UserStatus.APPROVED : UserStatus.REJECTED);
        assertThat(events.findAll()).singleElement().satisfies(event ->
                assertThat(event.getEventType()).isEqualTo(firstApproval ? "USER_APPROVED" : "USER_REJECTED"));
    }

    @ParameterizedTest
    @CsvSource({"true", "false"})
    void rolledBackDecisionLeavesNoEventAndAllowsAnotherDecision(boolean approval) {
        UUID target = pendingUser();
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            decide(target, approval);
            tx.setRollbackOnly();
        });
        assertThat(users.findById(target).orElseThrow().getUserStatus()).isEqualTo(UserStatus.PENDING);
        assertThat(events.count()).isZero();
        decide(target, !approval);
        assertThat(users.findById(target).orElseThrow().getUserStatus())
                .isEqualTo(approval ? UserStatus.REJECTED : UserStatus.APPROVED);
        assertThat(events.count()).isEqualTo(1);
    }

    private UUID pendingUser() {
        return users.saveAndFlush(User.create("pending01", "encoded-password", "U-APPROVAL",
                UserRole.HUB_ADMIN, UUID.randomUUID(), AffiliationType.HUB)).getUserId();
    }

    private void decide(UUID target, boolean approval) {
        if (approval) {
            service.approveUser(new ApproveUserCommand(target, REVIEWER), UserRole.MASTER_ADMIN);
        } else {
            service.rejectUser(new RejectUserCommand(target, REVIEWER, "가입 정보 불일치"), UserRole.MASTER_ADMIN);
        }
    }

    private void awaitDatabaseLock() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(), POSTGRES.getPassword()); var statement = connection.createStatement()) {
            while (System.nanoTime() < deadline) {
                try (var rows = statement.executeQuery("select count(*) from pg_stat_activity "
                        + "where datname = current_database() and wait_event_type = 'Lock' and query like '%p_user%'")) {
                    rows.next();
                    if (rows.getInt(1) > 0) return;
                }
                Thread.sleep(20);
            }
        }
        fail("The competing decision did not wait for the user row lock");
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(20, TimeUnit.SECONDS)) throw new AssertionError("Timed out waiting for commit");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new AssertionError(error);
        }
    }
}
