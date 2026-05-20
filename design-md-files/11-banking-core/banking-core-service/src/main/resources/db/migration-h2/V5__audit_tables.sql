CREATE TABLE audit.audit_events (
    audit_id        UUID NOT NULL DEFAULT RANDOM_UUID() PRIMARY KEY,
    event_type      VARCHAR(50) NOT NULL,
    entity_type     VARCHAR(30),
    entity_id       VARCHAR(50),
    actor_id        VARCHAR(50) NOT NULL,
    actor_role      VARCHAR(30),
    actor_ip        VARCHAR(45),
    session_id      VARCHAR(100),
    correlation_id  VARCHAR(100),
    old_state       VARCHAR(8000),
    new_state       VARCHAR(8000),
    occurred_at     TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
