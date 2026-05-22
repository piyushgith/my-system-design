-- V4: DB-level idempotency fallback (Redis is primary; this is the crash-safe backup)
CREATE TABLE idempotency_cache (
    idempotency_key VARCHAR(255) PRIMARY KEY,
    posting_id      UUID        NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at      TIMESTAMPTZ NOT NULL DEFAULT now() + INTERVAL '7 days'
);

CREATE INDEX idx_idempotency_expiry ON idempotency_cache(expires_at);

COMMENT ON TABLE idempotency_cache IS 'DB fallback for idempotency. Redis is primary. Expires after 7 days.';
