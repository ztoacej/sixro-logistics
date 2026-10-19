package com.sixro.logistics.user.infrastructure.messaging.outbox;

import com.sixro.logistics.user.domain.outbox.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * OutboxEvent의 JPA 저장소입니다.
 *
 * <p>Infrastructure 계층에서 Spring Data JPA를 사용해
 * Outbox 이벤트의 저장 및 발행 대상 조회를 담당합니다.</p>
 */
public interface OutboxEventJpaRepository
        extends JpaRepository<OutboxEvent, UUID> {

    /**
     * 재시도 시각이 된 PENDING/FAILED Outbox 이벤트를 예정 시각 순서로 조회하면서
     * 현재 Transaction이 처리할 행을 배타적으로 잠급니다.
     *
     * <p>{@code FOR UPDATE SKIP LOCKED}를 사용하므로 다른 인스턴스가
     * 이미 선점한 이벤트는 기다리지 않고 건너뜁니다.</p>
     *
     * <p>이 쿼리는 PostgreSQL 문법을 사용하므로 실제 동시성 검증은
     * PostgreSQL 또는 PostgreSQL Testcontainers에서 수행해야 합니다.</p>
     */
    @Query(
            value = """
                    SELECT *
                    FROM user_schema.p_user_outbox_events
                    WHERE status IN ('PENDING', 'FAILED')
                      AND (next_attempt_at IS NULL OR next_attempt_at <= :now)
                    ORDER BY COALESCE(next_attempt_at, created_at) ASC, event_id ASC
                    LIMIT 100
                    FOR UPDATE SKIP LOCKED
                    """,
            nativeQuery = true
    )
    List<OutboxEvent> findPublishableEventsForUpdate(@Param("now") LocalDateTime now);
}