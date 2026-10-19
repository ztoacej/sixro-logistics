package com.sixro.logistics.user.domain.outbox;

/**
 * Outbox 이벤트의 처리 상태를 나타냅니다.
 */
public enum OutboxEventStatus {

    /**
     * 아직 외부 메시징 시스템으로 발행되지 않은 상태입니다.
     */
    PENDING,

    /**
     * Kafka 발행이 완료된 상태입니다.
     */
    PUBLISHED,

    /**
     * 빠른 재시도에 실패한 상태입니다. 지정된 간격으로 복구 발행을 계속합니다.
     */
    FAILED
}