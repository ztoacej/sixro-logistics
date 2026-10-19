package com.sixro.logistics.auth.infrastructure.messaging.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixro.logistics.auth.application.service.UserAuthStateInvalidationService;
import com.sixro.logistics.auth.infrastructure.messaging.event.UserSecurityChangedMessage;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class UserSecurityEventConsumer {

    private static final String EVENT_ID_HEADER = "event-id";

    private final ObjectMapper objectMapper;
    private final UserAuthStateInvalidationService
            invalidationService;

    /**
     * 사용자 비활성화, 권한 변경, 소속 변경 이벤트를 소비하여
     * 기존 인증 Session과 Refresh Token을 무효화합니다.
     *
     * <p>event-id를 기록하여 중복 전달이 재로그인한 세션을 삭제하지 않게 합니다.</p>
     *
     * <p>event-id Header가 없는 기존 메시지도 처리할 수 있도록
     * Header를 필수값으로 강제하지 않습니다.</p>
     */
    @KafkaListener(
            topics = {
                    "user-deactivated",
                    "user-role-changed",
                    "user-affiliation-changed"
            },
            groupId = "${spring.kafka.consumer.group-id}"
    )
    public void consume(
            String payload,

            @Header(
                    name = EVENT_ID_HEADER,
                    required = false
            )
            String eventId
    ) {
        consumePayload(
                payload,
                eventId
        );
    }

    /**
     * 기존 단위 테스트와 직접 호출하는 코드의 호환성을 유지합니다.
     */
    public void consume(String payload) {
        consumePayload(
                payload,
                null
        );
    }

    private void consumePayload(
            String payload,
            String eventId
    ) {
        try {
            UserSecurityChangedMessage message =
                    objectMapper.readValue(
                            payload,
                            UserSecurityChangedMessage.class
                    );

            if (eventId == null || eventId.isBlank()) {
                invalidationService.invalidate(message.userId());
            } else {
                boolean applied = invalidationService.invalidate(message.userId(), UUID.fromString(eventId));
                if (!applied) {
                    log.debug("이미 처리한 사용자 보안 이벤트입니다. eventId={}", eventId);
                    return;
                }
            }

            log.info(
                    "사용자 인증 상태 무효화 완료. eventId={}, userId={}",
                    eventId,
                    message.userId()
            );

        } catch (JsonProcessingException exception) {
            /*
             * 예외를 밖으로 전달하여 Kafka 재시도 또는
             * 향후 설정할 DLT 처리가 가능하게 합니다.
             */
            throw new IllegalArgumentException(
                    "사용자 보안 이벤트 역직렬화에 실패했습니다.",
                    exception
            );
        }
    }
}
