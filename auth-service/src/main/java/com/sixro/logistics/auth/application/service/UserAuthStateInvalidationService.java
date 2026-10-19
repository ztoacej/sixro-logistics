package com.sixro.logistics.auth.application.service;

import com.sixro.logistics.auth.domain.repository.AuthStateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class UserAuthStateInvalidationService {
    private final AuthStateRepository authStateRepository;

    /** event-id가 없는 과거 메시지는 중복 판별 없이 원자적으로 삭제합니다. */
    public void invalidate(UUID userId) {
        invalidate(userId, UUID.randomUUID());
    }

    public boolean invalidate(UUID userId, UUID eventId) {
        return authStateRepository.invalidateUserState(
                Objects.requireNonNull(userId, "userId"),
                Objects.requireNonNull(eventId, "eventId"));
    }
}
