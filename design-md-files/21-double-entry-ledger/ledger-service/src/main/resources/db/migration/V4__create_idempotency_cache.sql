-- V4: RESERVED — not yet wired into the application.
-- Idempotency is currently enforced by postings.idempotency_key UNIQUE (authoritative)
-- fronted by a Redis pointer cache. This table is the intended future crash-safe
-- fallback for storing key -> posting_id outside the postings row; no code reads or
-- writes it today. Kept (not dropped) so Flyway schema history stays valid.
CREATE TABLE idempotency_cache (
    idempotency_key VARCHAR(255) PRIMARY KEY,
    posting_id      UUID        NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at      TIMESTAMPTZ NOT NULL DEFAULT now() + INTERVAL '7 days'
);

CREATE INDEX idx_idempotency_expiry ON idempotency_cache(expires_at);

COMMENT ON TABLE idempotency_cache IS 'DB fallback for idempotency. Redis is primary. Expires after 7 days.';
