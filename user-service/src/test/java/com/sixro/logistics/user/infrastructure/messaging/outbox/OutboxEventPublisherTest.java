package com.sixro.logistics.user.infrastructure.messaging.outbox;

import com.sixro.logistics.user.domain.outbox.*;
import com.sixro.logistics.user.infrastructure.messaging.kafka.KafkaUserEventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OutboxEventPublisherTest {
    @Mock OutboxEventRepository repository;
    @Mock KafkaUserEventPublisher kafka;
    @InjectMocks OutboxEventPublisher publisher;
    private OutboxEvent event;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(publisher, "retryDelayMillis", 5000L);
        ReflectionTestUtils.setField(publisher, "failedRetryDelayMillis", 60000L);
        event = OutboxEvent.create(UUID.randomUUID(), "USER", "USER_DEACTIVATED", "{}");
        ReflectionTestUtils.setField(event, "eventId", UUID.randomUUID());
        when(repository.findPublishableEventsForUpdate(any())).thenReturn(List.of(event));
    }

    @Test
    void failedEventIsPublishedWithOriginalEventIdentityWhenKafkaRecovers() {
        for (int i = 0; i < 3; i++) event.recordPublishFailure(LocalDateTime.now(ZoneOffset.UTC),
                Duration.ofSeconds(5), Duration.ofMinutes(1));
        doReturn(CompletableFuture.completedFuture(null)).when(kafka)
                .publish(anyString(), anyString(), anyString(), any(), anyString());
        publisher.publishPendingEvents();
        verify(kafka).publish("user-deactivated", event.getAggregateId().toString(), "{}",
                event.getEventId(), "USER_DEACTIVATED");
        assertThat(event.getStatus()).isEqualTo(OutboxEventStatus.PUBLISHED);
        assertThat(event.getNextAttemptAt()).isNull();
        verify(repository).save(event);
    }

    @Test
    void continuedKafkaFailurePersistsDelayedRetryInsteadOfAbandoningEvent() {
        doReturn(CompletableFuture.failedFuture(new IllegalStateException("Kafka unavailable")))
                .when(kafka).publish(anyString(), anyString(), anyString(), any(), anyString());
        var before = LocalDateTime.now(ZoneOffset.UTC);
        for (int i = 0; i < 4; i++) publisher.publishPendingEvents();
        assertThat(event.getStatus()).isEqualTo(OutboxEventStatus.FAILED);
        assertThat(event.getRetryCount()).isEqualTo(3);
        assertThat(event.getNextAttemptAt()).isAfterOrEqualTo(before.plusMinutes(1));
        verify(repository, times(4)).save(event);
    }
}
