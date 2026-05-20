-- Optional durable idempotency fallback (Redis remains primary store in V1)
CREATE TABLE paste.idempotency_keys (
    key             VARCHAR(128)    PRIMARY KEY,
    paste_id        UUID            NOT NULL,
    response_body   JSONB           NOT NULL,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    expires_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW() + INTERVAL '24 hours'
);

CREATE INDEX idx_idempotency_keys_expires_at
    ON paste.idempotency_keys (expires_at);
