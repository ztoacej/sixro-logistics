-- 기존 DB에 User Service 재배포 전에 실행합니다. 데이터는 삭제하지 않습니다.
-- 실행 계정: user_user 또는 DB 관리자. next_attempt_at은 UTC 기준입니다.
BEGIN;
ALTER TABLE user_schema.p_user_outbox_events
    ADD COLUMN IF NOT EXISTS next_attempt_at TIMESTAMP(6);
CREATE INDEX IF NOT EXISTS idx_p_user_outbox_retry_schedule
    ON user_schema.p_user_outbox_events (COALESCE(next_attempt_at, created_at), event_id)
    WHERE status IN ('PENDING', 'FAILED');
COMMIT;
