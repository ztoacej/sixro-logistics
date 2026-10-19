package com.sixro.logistics.auth.infrastructure.redis;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** 실제 Redis에서 Lua의 비교·삭제와 경쟁 요청의 결과를 검증합니다. */
@Testcontainers
class RedisLogoutConcurrencyTest {
    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7.4"))
                    .withExposedPorts(6379);
    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redis;
    private static RedisAuthStateRepository repository;
    private UUID userId;
    private UUID sessionId;
    private String jti;
    private static final Duration TTL = Duration.ofMinutes(5);

    @BeforeAll
    static void connect() {
        connectionFactory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        connectionFactory.afterPropertiesSet();
        redis = new StringRedisTemplate(connectionFactory);
        redis.afterPropertiesSet();
        repository = new RedisAuthStateRepository(redis);
    }

    @AfterAll
    static void close() {
        if (connectionFactory != null) connectionFactory.destroy();
    }

    @BeforeEach
    void login() {
        userId = UUID.randomUUID();
        sessionId = UUID.randomUUID();
        jti = UUID.randomUUID().toString();
        repository.saveLoginState(userId, "old-hash", sessionId, TTL);
    }

    @Test
    void matchingStateIsDeletedAndAccessTokenIsBlacklistedWithTtl() {
        assertThat(logout("old-hash", sessionId)).isTrue();
        assertThat(redis.hasKey("refresh:" + userId)).isFalse();
        assertThat(redis.hasKey("session:" + userId)).isFalse();
        assertThat(redis.opsForValue().get("blacklist:" + jti)).isEqualTo("logout");
        assertThat(redis.getExpire("blacklist:" + jti, TimeUnit.MILLISECONDS))
                .isPositive().isLessThanOrEqualTo(TTL.toMillis());
    }

    @Test
    void newLoginBetweenValidationAndDeletionSurvivesOldLogout() throws Exception {
        CountDownLatch validated = new CountDownLatch(1);
        CountDownLatch replaced = new CountDownLatch(1);
        CompletableFuture<Boolean> oldLogout = CompletableFuture.supplyAsync(() -> {
            String expectedHash = redis.opsForValue().get("refresh:" + userId);
            UUID expectedSession = UUID.fromString(redis.opsForValue().get("session:" + userId));
            validated.countDown();
            await(replaced);
            return logout(expectedHash, expectedSession);
        });
        UUID newSession = UUID.randomUUID();
        try {
            assertThat(validated.await(5, TimeUnit.SECONDS)).isTrue();
            repository.saveLoginState(userId, "new-hash", newSession, TTL);
        } finally {
            replaced.countDown();
        }
        assertThat(oldLogout.get(5, TimeUnit.SECONDS)).isFalse();
        assertState("new-hash", newSession);
        assertThat(redis.hasKey("blacklist:" + jti)).isFalse();
    }

    @Test
    void refreshRotationInSameSessionSurvivesOldLogout() {
        assertThat(repository.rotateRefreshToken(userId, "old-hash", "rotated-hash", TTL)).isTrue();
        assertThat(logout("old-hash", sessionId)).isFalse();
        assertState("rotated-hash", sessionId);
        assertThat(redis.hasKey("blacklist:" + jti)).isFalse();
    }

    @Test
    void matchingHashWithDifferentSessionCannotDeleteState() {
        assertThat(logout("old-hash", UUID.randomUUID())).isFalse();
        assertState("old-hash", sessionId);
        assertThat(redis.hasKey("blacklist:" + jti)).isFalse();
    }

    @Test
    void missingRefreshTokenDoesNotDeleteRemainingSession() {
        redis.delete("refresh:" + userId);
        assertThat(logout("old-hash", sessionId)).isFalse();
        assertThat(redis.opsForValue().get("session:" + userId)).isEqualTo(sessionId.toString());
        assertThat(redis.hasKey("blacklist:" + jti)).isFalse();
    }

    @Test
    void missingSessionDoesNotDeleteRemainingRefreshToken() {
        redis.delete("session:" + userId);
        assertThat(logout("old-hash", sessionId)).isFalse();
        assertThat(redis.opsForValue().get("refresh:" + userId)).isEqualTo("old-hash");
        assertThat(redis.hasKey("blacklist:" + jti)).isFalse();
    }

    @Test
    void expiredAccessTokenDoesNotCreatePlaceholderBlacklistKey() {
        assertThat(repository.clearLoginState(userId, "old-hash", sessionId, null, null)).isTrue();
        assertThat(redis.hasKey("refresh:" + userId)).isFalse();
        assertThat(redis.hasKey("session:" + userId)).isFalse();
        assertThat(redis.hasKey("blacklist:unused")).isFalse();
    }

    @Test
    void simultaneousLogoutsHaveOnlyOneSuccessfulDeletion() throws Exception {
        CyclicBarrier start = new CyclicBarrier(2);
        var first = CompletableFuture.supplyAsync(() -> concurrentLogout(start));
        var second = CompletableFuture.supplyAsync(() -> concurrentLogout(start));
        boolean a = first.get(5, TimeUnit.SECONDS);
        boolean b = second.get(5, TimeUnit.SECONDS);
        assertThat(a ^ b).isTrue();
        assertThat(redis.hasKey("refresh:" + userId)).isFalse();
        assertThat(redis.hasKey("session:" + userId)).isFalse();
    }

    @Test
    void repeatedSecurityEventDoesNotDeleteNewLogin() {
        UUID eventId = UUID.randomUUID();
        assertThat(repository.invalidateUserState(userId, eventId)).isTrue();
        assertThat(redis.hasKey("refresh:" + userId)).isFalse();
        assertThat(redis.hasKey("session:" + userId)).isFalse();
        UUID newSession = UUID.randomUUID();
        repository.saveLoginState(userId, "new-hash", newSession, TTL);
        assertThat(repository.invalidateUserState(userId, eventId)).isFalse();
        assertState("new-hash", newSession);
        assertThat(redis.getExpire("security-event:" + eventId)).isEqualTo(-1L);
        assertThat(repository.invalidateUserState(userId, UUID.randomUUID())).isTrue();
        assertThat(redis.hasKey("session:" + userId)).isFalse();
    }

    private boolean concurrentLogout(CyclicBarrier start) {
        try {
            start.await(5, TimeUnit.SECONDS);
            return logout("old-hash", sessionId);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Timed out waiting for login");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private boolean logout(String hash, UUID expectedSession) {
        return repository.clearLoginState(userId, hash, expectedSession, jti, TTL);
    }

    private void assertState(String hash, UUID expectedSession) {
        assertThat(redis.opsForValue().get("refresh:" + userId)).isEqualTo(hash);
        assertThat(redis.opsForValue().get("session:" + userId)).isEqualTo(expectedSession.toString());
    }
}
