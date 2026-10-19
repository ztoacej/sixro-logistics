package com.sixro.logistics.user.domain.outbox;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class OutboxEventTest {
    @Test
    void repeatedFailuresRemainRecoverableAndRespectExistingRetryCountConstraint() {
        var event = OutboxEvent.create(UUID.randomUUID(), "USER", "USER_DEACTIVATED", "{}");
        var now = LocalDateTime.of(2026, 10, 19, 0, 0);
        event.recordPublishFailure(now, Duration.ofSeconds(5), Duration.ofMinutes(1));
        assertThat(event.getStatus()).isEqualTo(OutboxEventStatus.PENDING);
        assertThat(event.getNextAttemptAt()).isEqualTo(now.plusSeconds(5));
        for (int i = 0; i < 20; i++) {
            event.recordPublishFailure(now, Duration.ofSeconds(5), Duration.ofMinutes(1));
        }
        assertThat(event.getStatus()).isEqualTo(OutboxEventStatus.FAILED);
        assertThat(event.getRetryCount()).isEqualTo(3);
        assertThat(event.getNextAttemptAt()).isEqualTo(now.plusMinutes(1));
        event.markPublished();
        assertThat(event.getStatus()).isEqualTo(OutboxEventStatus.PUBLISHED);
        assertThat(event.getPublishedAt()).isNotNull();
        assertThat(event.getNextAttemptAt()).isNull();
    }

    @Test
    void nonPositiveDelayIsRejectedBeforeStateMutation() {
        var event = OutboxEvent.create(UUID.randomUUID(), "USER", "USER_DEACTIVATED", "{}");
        assertThatThrownBy(() -> event.recordPublishFailure(LocalDateTime.now(),
                Duration.ZERO, Duration.ofMinutes(1))).isInstanceOf(IllegalArgumentException.class);
        assertThat(event.getRetryCount()).isZero();
    }
}
