CREATE TABLE accounts.open_idempotency_claim (
    idempotency_key   VARCHAR(100) PRIMARY KEY,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
