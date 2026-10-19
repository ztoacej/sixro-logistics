package com.sixro.logistics.user.domain.outbox;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.time.Duration;
import java.util.UUID;

/**
 * User Service의 도메인 변경과 함께 저장되는
 * Transactional Outbox 이벤트입니다.
 *
 * <p>도메인 데이터 변경과 Outbox 이벤트 저장을
 * 동일한 DB Transaction으로 처리하여
 * DB 변경과 이벤트 발행 요청 사이의 불일치를 줄입니다.</p>
 */
@Entity
@Table(schema = "user_schema", name = "p_user_outbox_events")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "event_id", nullable = false, updatable = false)
    private UUID eventId;

    /**
     * 이벤트가 발생한 Aggregate의 식별자입니다.
     *
     * <p>User 이벤트에서는 userId가 저장됩니다.</p>
     */
    @Column(name = "aggregate_id", nullable = false)
    private UUID aggregateId;

    /**
     * 이벤트가 발생한 Aggregate 종류입니다.
     *
     * <p>현재 User Service에서는 USER를 사용합니다.</p>
     */
    @Column(name = "aggregate_type", nullable = false, length = 50)
    private String aggregateType;

    /**
     * 발행할 이벤트 종류입니다.
     *
     * <p>예: USER_APPROVED, USER_REJECTED, USER_DEACTIVATED</p>
     */
    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    /**
     * 외부 메시징 시스템으로 전달할 이벤트 Payload입니다.
     */
    @Column(name = "payload", nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private OutboxEventStatus status;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "published_at")
    private LocalDateTime publishedAt;

    @Column(name = "retry_count", nullable = false)
    private int retryCount;

    // UTC 기준 다음 발행 시각. null인 기존 PENDING/FAILED 행도 즉시 재처리합니다.
    @Column(name = "next_attempt_at")
    private LocalDateTime nextAttemptAt;

    private OutboxEvent(
            UUID aggregateId,
            String aggregateType,
            String eventType,
            String payload
    ) {
        this.aggregateId = aggregateId;
        this.aggregateType = aggregateType;
        this.eventType = eventType;
        this.payload = payload;
        this.status = OutboxEventStatus.PENDING;
        this.retryCount = 0;
        this.createdAt = LocalDateTime.now();
    }

    /**
     * 새로운 Outbox 이벤트를 생성합니다.
     */
    public static OutboxEvent create(
            UUID aggregateId,
            String aggregateType,
            String eventType,
            String payload
    ) {
        return new OutboxEvent(
                aggregateId,
                aggregateType,
                eventType,
                payload
        );
    }

    /**
     * Kafka 발행 완료 상태로 변경합니다.
     */
    public void markPublished() {
        this.status = OutboxEventStatus.PUBLISHED;
        this.publishedAt = LocalDateTime.now();
        this.nextAttemptAt = null;
    }

    /** 빠른 재시도 3회 이후에는 FAILED 상태로 느리게 재시도합니다. */
    public void recordPublishFailure(LocalDateTime now, Duration retryDelay, Duration failedDelay) {
        if (retryDelay.isZero() || retryDelay.isNegative()
                || failedDelay.isZero() || failedDelay.isNegative()) {
            throw new IllegalArgumentException("재시도 간격은 양수여야 합니다.");
        }
        // 기존 DB의 retry_count BETWEEN 0 AND 3 제약을 유지합니다.
        this.retryCount = Math.min(this.retryCount + 1, 3);
        this.status = retryCount >= 3 ? OutboxEventStatus.FAILED : OutboxEventStatus.PENDING;
        this.publishedAt = null;
        this.nextAttemptAt = now.plus(status == OutboxEventStatus.FAILED ? failedDelay : retryDelay);
    }
}
