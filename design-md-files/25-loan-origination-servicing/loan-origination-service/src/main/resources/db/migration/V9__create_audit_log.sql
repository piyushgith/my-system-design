-- Append-only audit log — every state transition for every entity
-- Regulatory requirement: never UPDATE or DELETE rows here
CREATE TABLE audit_log (
    id              BIGSERIAL    PRIMARY KEY,
    entity_type     VARCHAR(64)  NOT NULL,
    entity_id       UUID         NOT NULL,
    action          VARCHAR(64)  NOT NULL,
    actor_id        UUID,
    actor_type      VARCHAR(32),              -- USER, SYSTEM, EXTERNAL_API
    old_status      VARCHAR(32),
    new_status      VARCHAR(32),
    change_payload  JSONB,
    occurred_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    correlation_id  VARCHAR(64)
);

CREATE INDEX idx_audit_entity ON audit_log (entity_type, entity_id, occurred_at DESC);
CREATE INDEX idx_audit_actor  ON audit_log (actor_id)   WHERE actor_id IS NOT NULL;
CREATE INDEX idx_audit_time   ON audit_log (occurred_at DESC);

-- Prevent accidental updates/deletes on audit rows (belt-and-suspenders)
CREATE RULE audit_no_update AS ON UPDATE TO audit_log DO INSTEAD NOTHING;
CREATE RULE audit_no_delete AS ON DELETE TO audit_log DO INSTEAD NOTHING;
