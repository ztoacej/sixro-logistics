package com.sixro.logistics.auth.application.service;

import com.sixro.logistics.auth.domain.repository.AuthStateRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserAuthStateInvalidationServiceTest {
    @Mock AuthStateRepository repository;
    @InjectMocks UserAuthStateInvalidationService service;

    @Test
    void eventIdAndUserIdArePassedToAtomicInvalidation() {
        UUID userId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        when(repository.invalidateUserState(userId, eventId)).thenReturn(true);
        assertThat(service.invalidate(userId, eventId)).isTrue();
        verify(repository).invalidateUserState(userId, eventId);
    }

    @Test
    void duplicateEventReturnsFalse() {
        UUID userId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        when(repository.invalidateUserState(userId, eventId)).thenReturn(false);
        assertThat(service.invalidate(userId, eventId)).isFalse();
    }

    @Test
    void redisFailureIsPropagatedForKafkaRetry() {
        UUID userId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        var failure = new IllegalStateException("Redis unavailable");
        when(repository.invalidateUserState(userId, eventId)).thenThrow(failure);
        assertThatThrownBy(() -> service.invalidate(userId, eventId)).isSameAs(failure);
    }

    @Test
    void legacyMessageStillUsesAtomicDeletion() {
        UUID userId = UUID.randomUUID();
        service.invalidate(userId);
        verify(repository).invalidateUserState(eq(userId), any(UUID.class));
    }
}
