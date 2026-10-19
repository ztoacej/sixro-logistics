CREATE SCHEMA user_schema;
CREATE TABLE user_schema.p_user_outbox_events (
    event_id uuid PRIMARY KEY,
    aggregate_id uuid NOT NULL,
    aggregate_type varchar(50) NOT NULL,
    event_type varchar(100) NOT NULL,
    payload text NOT NULL,
    status varchar(30) NOT NULL CHECK (status IN ('PENDING','FAILED','PUBLISHED')),
    created_at timestamp(6) NOT NULL,
    published_at timestamp(6),
    retry_count integer NOT NULL DEFAULT 0 CHECK (retry_count BETWEEN 0 AND 3)
);
