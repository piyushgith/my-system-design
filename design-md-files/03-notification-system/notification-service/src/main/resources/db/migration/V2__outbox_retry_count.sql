-- =============================================================================
-- V2__outbox_retry_count.sql
-- Add retry tracking to outbox_events; fix index to cover status column.
-- =============================================================================

ALTER TABLE outbox_events
    ADD COLUMN retry_count INTEGER NOT NULL DEFAULT 0;

-- Replace the partial-index-only approach with a composite covering index
-- that matches the JPA @Index and the JPQL query (WHERE status = 'PENDING' ORDER BY created_at).
DROP INDEX IF EXISTS idx_outbox_pending;
DROP INDEX IF EXISTS idx_outbox_unpublished;

CREATE INDEX idx_outbox_status_created
    ON outbox_events (status, created_at);
