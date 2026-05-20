CREATE TABLE audit.audit_events (
    audit_id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    event_type      VARCHAR(50) NOT NULL,
    entity_type     VARCHAR(30),
    entity_id       VARCHAR(50),
    actor_id        VARCHAR(50) NOT NULL,
    actor_role      VARCHAR(30),
    actor_ip        VARCHAR(45),
    session_id      VARCHAR(100),
    correlation_id  VARCHAR(100),
    old_state       JSONB,
    new_state       JSONB,
    occurred_at     TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
